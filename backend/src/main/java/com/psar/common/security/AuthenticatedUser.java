package com.psar.common.security;

import java.util.UUID;

/**
 * The logged-in caller, set as the Spring Security principal by the JWT filter.
 * Controllers get it with {@code @AuthenticationPrincipal AuthenticatedUser me}.
 * <p>
 * {@code role} is a String ("ADMIN", "CUSTOMER", ...) because common may not depend on
 * user.entity.Role. The granted authority is "ROLE_" + role, so {@code hasRole('ADMIN')} works.
 */
public record AuthenticatedUser(
        Long userId,
        UUID publicId,
        String email,
        String role
) {
    public static final String ROLE_PREFIX = "ROLE_";

    public boolean hasRole(String role) {
        return this.role.equals(role);
    }

    public String authority() {
        return ROLE_PREFIX + role;
    }
}
