package com.psar.user.dto.response;

import com.psar.user.entity.Role;

import java.time.Instant;
import java.util.UUID;

/** Admin view of an account. Never includes password_hash. */
public record UserResponse(
        UUID publicId,
        String email,
        String fullName,
        String position,
        Role role,
        boolean active,
        boolean emailVerified,
        Instant lastLoginAt,
        Instant createdAt
) {
}
