package com.psar.catalog.dto.response;

import java.util.UUID;

/** Just enough of a product to point at it from another module (e.g. a promotion's targets). */
public record ProductReference(
        Long id,
        UUID publicId,
        String slug,
        String name
) {
}
