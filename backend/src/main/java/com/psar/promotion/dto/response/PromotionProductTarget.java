package com.psar.promotion.dto.response;

import java.util.UUID;

/** A product an item promotion applies to. */
public record PromotionProductTarget(
        UUID publicId,
        String slug,
        String name
) {
}
