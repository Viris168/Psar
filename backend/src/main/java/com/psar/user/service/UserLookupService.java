package com.psar.user.service;

import com.psar.user.dto.response.UserSummary;
import com.psar.user.entity.User;
import com.psar.user.entity.UserProfile;
import com.psar.user.repository.UserProfileRepository;
import com.psar.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** Read-only user lookups by id for other modules (which can't use the user entities), e.g. audit logs. */
@Service
@RequiredArgsConstructor
public class UserLookupService {

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;

    /** Two queries for a whole page. Ids that don't exist are simply missing from the map. */
    @Transactional(readOnly = true)
    public Map<Long, UserSummary> summaries(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = userProfileRepository.findAllByUserIdIn(userIds).stream()
                .filter(profile -> profile.getFullName() != null)
                .collect(Collectors.toMap(profile -> profile.getUser().getId(), UserProfile::getFullName));
        return userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId,
                        user -> new UserSummary(user.getPublicId(), user.getEmail(), names.get(user.getId()))));
    }

    /** The internal id behind a public id, for filtering another module's rows by user. */
    @Transactional(readOnly = true)
    public Optional<Long> idOf(UUID publicId) {
        return userRepository.findByPublicId(publicId).map(User::getId);
    }
}
