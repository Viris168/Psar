package com.psar.order.service.pricing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PricingCalculatorTest {

    private final PricingCalculator calculator = new PricingCalculator();

    @Test
    void total_normalOrder_subtractsDiscountsAndAddsDeliveryFee() {
        BigDecimal total = calculator.total(new BigDecimal("60.00"), new BigDecimal("10.00"), new BigDecimal("1.50"));

        assertThat(total).isEqualByComparingTo("51.50");
    }

    @Test
    void total_discountLargerThanSubtotal_neverBelowZero() {
        BigDecimal total = calculator.total(new BigDecimal("5.00"), new BigDecimal("20.00"), BigDecimal.ZERO);

        assertThat(total).isEqualByComparingTo("0.00");
    }

    @Test
    void money_roundsHalfUpToTwoDecimals() {
        assertThat(PricingCalculator.money(new BigDecimal("1.005"))).isEqualByComparingTo("1.01");
    }
}
