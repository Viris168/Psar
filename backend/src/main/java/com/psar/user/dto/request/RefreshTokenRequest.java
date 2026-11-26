package com.psar.user.dto.request;

import jakarta.validation.constraints.NotBlank;

/** Used by both refresh (rotation) and logout. */
public record RefreshTokenRequest(
        @NotBlank String refreshToken
) {
}
