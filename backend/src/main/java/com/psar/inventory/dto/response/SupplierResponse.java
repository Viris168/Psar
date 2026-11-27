package com.psar.inventory.dto.response;

import java.time.Instant;
import java.util.UUID;

public record SupplierResponse(
        UUID publicId,
        String name,
        String phone,
        String email,
        String address,
        String note,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
}
