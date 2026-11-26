package com.psar.user.dto.request;

import com.psar.user.entity.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Admin creates a STAFF / MANAGER / ADMIN account. Customers self-register instead. */
public record CreateStaffRequest(
        @NotBlank @Email @Size(max = 150) String email,
        // BCrypt ignores bytes after 72
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(max = 100) String fullName,
        @NotNull Role role,
        @Size(max = 50) String position
) {
}
