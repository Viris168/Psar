package com.psar.user.service;

import com.psar.user.entity.Role;
import com.psar.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** User lookups for other modules, which can't use the user entities (e.g. notification recipients). */
@Service
@RequiredArgsConstructor
public class UserDirectoryService {

    private final UserRepository userRepository;

    /**
     * Internal ids of the active users with one of these roles ({@code "ADMIN"}, {@code "MANAGER"}, …).
     *
     * @throws IllegalArgumentException for an unknown role name
     */
    @Transactional(readOnly = true)
    public List<Long> activeUserIdsWithRoles(Set<String> roles) {
        if (roles.isEmpty()) {
            return List.of();
        }
        Set<Role> parsed = roles.stream().map(Role::valueOf).collect(Collectors.toSet());
        return userRepository.findActiveIdsByRoleIn(parsed);
    }
}
