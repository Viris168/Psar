package com.psar.catalog.dto.response;

/** A category as shown inside a product (and in the breadcrumb). */
public record CategoryRef(
        String slug,
        String name,
        String nameKh
) {
}
