package com.psar.order.service.pricing;

import java.math.BigDecimal;
import java.util.List;

/**
 * What {@link PricingCalculator#price} needs. Prices come from the database, never from the client.
 * Phase 6 adds the promotions that apply, Phase 7 the reward voucher, Phase 8 the member flag.
 */
public record PricingInput(List<Line> lines, BigDecimal deliveryFee) {

    public PricingInput {
        lines = List.copyOf(lines);
    }

    public record Line(Long productId, int quantity, BigDecimal unitPrice) {
    }
}
