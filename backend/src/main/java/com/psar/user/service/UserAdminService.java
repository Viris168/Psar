package com.psar.user.service;

import com.psar.common.exception.BadRequestException;
import com.psar.common.exception.BusinessRuleException;
import com.psar.common.exception.ConflictException;
import com.psar.common.exception.ErrorCode;
import com.psar.common.exception.NotFoundException;
import com.psar.common.security.AuthenticatedUser;
import com.psar.common.web.PageResponse;
import com.psar.user.dto.request.CreateStaffRequest;
import com.psar.user.dto.request.UpdateUserRoleRequest;
import com.psar.user.dto.request.UpdateUserStatusRequest;
import com.psar.user.dto.response.UserResponse;
import com.psar.user.entity.Role;
import com.psar.user.entity.User;
import com.psar.user.entity.UserProfile;
import com.psar.user.mapper.UserMapper;
import com.psar.user.repository.RefreshTokenRepository;
import com.psar.user.repository.UserProfileRepository;
import com.psar.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Admin account management: list, create staff accounts, change role, enable/disable. */
@Service
@RequiredArgsConstructor
public class UserAdminService {

    static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> SORTABLE = Set.of("createdAt", "email", "role", "lastLoginAt");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");
    private static final Set<Role> STAFF_ROLES = Set.of(Role.STAFF, Role.MANAGER, Role.ADMIN);

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    /** {@code role} and {@code q} (part of the email, case-insensitive) are optional filters. */
    @Transactional(readOnly = true)
    public PageResponse<UserResponse> list(Pageable pageable, Role role, String q) {
        Specification<User> spec = (root, query, cb) -> cb.conjunction();
        if (role != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("role"), role));
        }
        if (StringUtils.hasText(q)) {
            String pattern = "%" + escapeLike(q.trim().toLowerCase(Locale.ROOT)) + "%";
            spec = spec.and((root, query, cb) -> cb.like(root.get("email"), pattern, '\\'));
        }
        Page<User> users = userRepository.findAll(spec, safePage(pageable));
        Map<Long, UserProfile> profiles = userProfileRepository
                .findAllByUserIdIn(users.map(User::getId).getContent()).stream()
                .collect(Collectors.toMap(profile -> profile.getUser().getId(), Function.identity()));
        return PageResponse.from(users.map(user -> userMapper.toResponse(user, profiles.get(user.getId()))));
    }

    @Transactional(readOnly = true)
    public UserResponse get(UUID publicId) {
        return toResponse(find(publicId));
    }

    @Transactional
    public UserResponse createStaff(CreateStaffRequest request) {
        if (!STAFF_ROLES.contains(request.role())) {
            throw new BadRequestException("Only STAFF, MANAGER or ADMIN accounts can be created here",
                    Map.of("role", "must be STAFF, MANAGER or ADMIN"));
        }
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException(ErrorCode.EMAIL_ALREADY_EXISTS, "Email is already registered");
        }

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(request.role());
        user = userRepository.save(user);

        UserProfile profile = new UserProfile();
        profile.setUser(user);
        profile.setFullName(request.fullName().trim());
        profile.setPosition(StringUtils.hasText(request.position()) ? request.position().trim() : null);
        userProfileRepository.save(profile);

        return userMapper.toResponse(user, profile);
    }

    @Transactional
    public UserResponse changeRole(AuthenticatedUser me, UUID publicId, UpdateUserRoleRequest request) {
        User user = find(publicId);
        boolean demotingAdmin = user.getRole() == Role.ADMIN && request.role() != Role.ADMIN;
        if (demotingAdmin) {
            if (isSelf(me, user)) {
                throw new BusinessRuleException("You can't change your own role");
            }
            assertNotLastActiveAdmin(user);
        }
        user.setRole(request.role());
        return toResponse(userRepository.save(user));
    }

    /** Disabling also revokes every refresh token, so the user is logged out everywhere. */
    @Transactional
    public UserResponse changeStatus(AuthenticatedUser me, UUID publicId, UpdateUserStatusRequest request) {
        User user = find(publicId);
        boolean disabling = user.isActive() && !request.active();
        if (disabling) {
            if (isSelf(me, user)) {
                throw new BusinessRuleException("You can't disable your own account");
            }
            if (user.getRole() == Role.ADMIN) {
                assertNotLastActiveAdmin(user);
            }
        }
        user.setActive(request.active());
        user = userRepository.save(user);
        if (!request.active()) {
            // flushes the user change first; clears the persistence context afterwards
            refreshTokenRepository.revokeAllByUserId(user.getId());
        }
        return toResponse(user);
    }

    private void assertNotLastActiveAdmin(User target) {
        boolean anotherActiveAdmin = userRepository.findAllByRoleAndActiveTrueOrderByIdAsc(Role.ADMIN).stream()
                .anyMatch(admin -> !admin.getId().equals(target.getId()));
        if (!anotherActiveAdmin) {
            throw new BusinessRuleException("The last active ADMIN can't be disabled or demoted");
        }
    }

    private static boolean isSelf(AuthenticatedUser me, User user) {
        return me != null && user.getId().equals(me.userId());
    }

    private UserResponse toResponse(User user) {
        return userMapper.toResponse(user, userProfileRepository.findByUserId(user.getId()).orElse(null));
    }

    private User find(UUID publicId) {
        return userRepository.findByPublicId(publicId).orElseThrow(() -> NotFoundException.of("User"));
    }

    /** Caps the page size and only allows sorting on known properties (unknown ones would be a 500). */
    private static Pageable safePage(Pageable pageable) {
        Sort sort = Sort.by(pageable.getSort().stream()
                .filter(order -> SORTABLE.contains(order.getProperty()))
                .toList());
        return PageRequest.of(pageable.getPageNumber(),
                Math.min(pageable.getPageSize(), MAX_PAGE_SIZE),
                sort.isSorted() ? sort : DEFAULT_SORT);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
