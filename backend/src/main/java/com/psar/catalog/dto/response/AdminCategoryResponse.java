package com.psar.catalog.dto.response;

import java.time.Instant;

/** Admin view of a category, active or not. {@code parentSlug} null = top level. */
public record AdminCategoryResponse(
        String slug,
        String name,
        String nameKh,
        String parentSlug,
        String imageUrl,
        int sortOrder,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
}
