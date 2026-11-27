package com.psar.inventory.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admin create/update. {@code active} is optional: omitted on create = active,
 * omitted on update = unchanged (set true to restore a deleted supplier).
 */
public record SupplierRequest(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 30) String phone,
        @Email @Size(max = 150) String email,
        @Size(max = 1000) String address,
        @Size(max = 2000) String note,
        Boolean active
) {
}
