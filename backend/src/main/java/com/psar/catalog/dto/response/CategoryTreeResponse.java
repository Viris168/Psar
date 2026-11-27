package com.psar.catalog.dto.response;

import java.util.List;

/** Storefront category tree: active top-level categories with their active subcategories. */
public record CategoryTreeResponse(
        String slug,
        String name,
        String nameKh,
        String imageUrl,
        List<CategoryTreeResponse> children
) {
}
