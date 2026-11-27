package com.psar.catalog.dto.response;

import java.util.UUID;

/** A product image. {@code publicId} is the {@code imageId} in the image endpoints. */
public record ProductImageResponse(
        UUID publicId,
        String imageUrl,
        boolean main,
        int sortOrder
) {
}
