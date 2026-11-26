package com.psar.user.dto.request;

import jakarta.validation.constraints.NotNull;

/** {@code active = false} disables the account and logs it out everywhere. */
public record UpdateUserStatusRequest(
        @NotNull Boolean active
) {
}
