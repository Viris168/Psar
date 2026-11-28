package com.psar.promotion.dto.request;

import com.psar.promotion.entity.PromotionScope;
import com.psar.promotion.entity.PromotionType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Admin create / update (PUT replaces every field). Rules that depend on the type (value, buy / get, scope,
 * targets) are checked by AdminPromotionService and come back as 422 with field details.
 */
public record PromotionRequest(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 2000) String description,
        @Size(max = 500) String bannerUrl,
        @NotNull PromotionType type,
        @Schema(description = "PERCENTAGE: 1–100 (%); FIXED_AMOUNT: $ off per unit (item) or per order; "
                + "empty for BUY_X_GET_Y and FREE_DELIVERY")
        @Digits(integer = 10, fraction = 2) BigDecimal discountValue,
        @Schema(description = "PERCENTAGE only: never more than this off")
        @Digits(integer = 10, fraction = 2) BigDecimal maxDiscount,
        @Schema(description = "BUY_X_GET_Y only") Integer buyQuantity,
        @Schema(description = "BUY_X_GET_Y only") Integer getQuantity,
        @NotNull PromotionScope applyTo,
        @Schema(description = "Product public ids; required for apply_to PRODUCTS")
        @Size(max = 500) List<@NotNull UUID> productIds,
        @Schema(description = "Category slugs; required for apply_to CATEGORIES (a top-level one covers its "
                + "subcategories)")
        @Size(max = 100) List<@NotBlank String> categorySlugs,
        @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal minOrderAmount,
        @Schema(description = "Empty = automatic. 3–30 characters A–Z, 0–9, _ or -; stored upper-case")
        @Size(max = 30) String couponCode,
        Boolean membersOnly,
        @Positive Integer usageLimit,
        @Positive Integer perUserLimit,
        @NotNull Instant startDate,
        @NotNull Instant endDate,
        @Schema(description = "Omitted = true") Boolean active,
        @Schema(description = "Announce it to every customer when it starts (omitted = false)")
        Boolean broadcastOnStart
) {
}
