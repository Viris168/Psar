package com.psar.inventory.dto.response;

import java.util.UUID;

/** Supplier as shown inside other responses (e.g. an admin product). */
public record SupplierSummary(
        UUID publicId,
        String name
) {
}
