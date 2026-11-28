package com.psar.catalog.dto.response;

/** Just enough of a category to point at it from another module (e.g. a promotion's targets). */
public record CategoryReference(
        Long id,
        String slug,
        String name
) {
}
