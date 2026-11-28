package com.psar.order.dto.request;

import com.psar.order.entity.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Places an order from the current cart. There are deliberately no price or total fields: everything is
 * recalculated on the backend (unknown fields such as a client-sent price are ignored).
 *
 * @param couponCode optional, any case; a code that can't be used fails checkout (422 COUPON_INVALID), it is
 *                   never silently dropped
 */
public record CheckoutRequest(
        @NotNull UUID addressPublicId,
        @NotNull PaymentMethod paymentMethod,
        @Size(max = 500) String note,
        @Size(max = 30) String couponCode
) {
}
