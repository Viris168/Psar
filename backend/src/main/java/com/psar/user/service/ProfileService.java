package com.psar.user.service;

import com.psar.common.exception.NotFoundException;
import com.psar.common.security.AuthenticatedUser;
import com.psar.user.dto.request.UpdateProfileRequest;
import com.psar.user.dto.response.ProfileResponse;
import com.psar.user.entity.UserProfile;
import com.psar.user.mapper.UserProfileMapper;
import com.psar.user.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The current user's own profile ("me"). Any role. */
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final UserProfileRepository userProfileRepository;
    private final UserProfileMapper userProfileMapper;

    @Transactional(readOnly = true)
    public ProfileResponse getMe(AuthenticatedUser me) {
        return userProfileMapper.toResponse(findProfile(me));
    }

    /** Position and points balance are not editable here (see UserProfileMapper#update). */
    @Transactional
    public ProfileResponse updateMe(AuthenticatedUser me, UpdateProfileRequest request) {
        UserProfile profile = findProfile(me);
        userProfileMapper.update(request, profile);
        return userProfileMapper.toResponse(userProfileRepository.saveAndFlush(profile));
    }

    private UserProfile findProfile(AuthenticatedUser me) {
        return userProfileRepository.findByUserIdWithUser(me.userId())
                .orElseThrow(() -> NotFoundException.of("Profile"));
    }
}
