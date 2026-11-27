package com.psar.catalog.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

/**
 * Admin create/update. {@code parentSlug} null = top-level category.
 * {@code sortOrder} and {@code active} are optional: omitted on create = 0 / active, omitted on update = unchanged.
 * The slug is generated from the name on create and kept on rename.
 */
public record CategoryRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 100) String nameKh,
        @Size(max = 120) String parentSlug,
        @URL @Size(max = 255) String imageUrl,
        @Min(0) Integer sortOrder,
        Boolean active
) {
}
