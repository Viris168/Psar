package com.psar.user.dto.response;

import com.psar.user.entity.Gender;
import com.psar.user.entity.Language;
import com.psar.user.entity.Role;

import java.time.LocalDate;
import java.util.UUID;

/** The current user ("me"): account basics plus profile. */
public record ProfileResponse(
        UUID publicId,
        String email,
        Role role,
        boolean emailVerified,
        String fullName,
        String fullNameKh,
        Gender gender,
        LocalDate dateOfBirth,
        String phone,
        String avatarUrl,
        String position,
        String telegramChatId,
        Language language,
        int pointsBalance
) {
}
