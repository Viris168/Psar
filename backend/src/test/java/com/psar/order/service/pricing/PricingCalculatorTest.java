package com.psar.order.service.pricing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure unit tests: no Spring context. */
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
        assertThat(total.scale()).isEqualTo(2);
    }

    @Test
    void money_roundsHalfUpToTwoDecimals() {
        assertThat(PricingCalculator.money(new BigDecimal("1.005"))).isEqualByComparingTo("1.01");
        assertThat(PricingCalculator.money(new BigDecimal("1.004"))).isEqualByComparingTo("1.00");
    }

    @Test
    void price_oneLine_lineTotalIsUnitPriceTimesQuantity() {
        PricingResult result = calculator.price(input("1.00", line(1L, 3, "2.50")));

        assertThat(result.lines()).singleElement().satisfies(line -> {
            assertThat(line.productId()).isEqualTo(1L);
            assertThat(line.quantity()).isEqualTo(3);
            assertThat(line.unitPrice()).isEqualTo(new BigDecimal("2.50"));
            assertThat(line.lineTotal()).isEqualTo(new BigDecimal("7.50"));
        });
        assertThat(result.subtotal()).isEqualTo(new BigDecimal("7.50"));
        assertThat(result.deliveryFee()).isEqualTo(new BigDecimal("1.00"));
        assertThat(result.total()).isEqualTo(new BigDecimal("8.50"));
    }

    @Test
    void price_unroundedAmounts_everyAmountHalfUpScaleTwo() {
        PricingResult result = calculator.price(input("1.255",
                line(1L, 3, "1.335"),      // 4.005 -> 4.01
                line(2L, 1, "0.125")));    // 0.125 -> 0.13

        assertThat(result.lines()).extracting(PricingResult.Line::lineTotal)
                .containsExactly(new BigDecimal("4.01"), new BigDecimal("0.13"));
        assertThat(result.lines()).extracting(PricingResult.Line::unitPrice)
                .containsExactly(new BigDecimal("1.34"), new BigDecimal("0.13"));
        assertThat(result.subtotal()).isEqualTo(new BigDecimal("4.14"));
        assertThat(result.deliveryFee()).isEqualTo(new BigDecimal("1.26"));
        assertThat(result.total()).isEqualTo(new BigDecimal("5.40"));
        assertThat(List.of(result.subtotal(), result.productDiscount(), result.orderDiscount(),
                result.rewardDiscount(), result.deliveryFee(), result.total()))
                .allSatisfy(amount -> assertThat(amount.scale()).isEqualTo(2));
    }

    @Test
    void price_phaseFour_discountsAreZero() {
        PricingResult result = calculator.price(input("2.00", line(1L, 2, "10.00")));

        assertThat(result.productDiscount()).isEqualTo(new BigDecimal("0.00"));
        assertThat(result.orderDiscount()).isEqualTo(new BigDecimal("0.00"));
        assertThat(result.rewardDiscount()).isEqualTo(new BigDecimal("0.00"));
        assertThat(result.lines()).singleElement().satisfies(line -> {
            assertThat(line.freeQuantity()).isZero();
            assertThat(line.discountAmount()).isEqualTo(new BigDecimal("0.00"));
        });
        assertThat(result.total()).isEqualTo(new BigDecimal("22.00"));
    }

    @Test
    void price_freeProductsAndNoFee_totalIsZeroNotNegative() {
        PricingResult result = calculator.price(input("0", line(1L, 1, "0.00")));

        assertThat(result.total()).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void price_manyLines_subtotalIsSumInInputOrder() {
        PricingInput.Line[] lines = IntStream.rangeClosed(1, 50)
                .mapToObj(i -> line((long) i, i, "0.99"))
                .toArray(PricingInput.Line[]::new);

        PricingResult result = calculator.price(input("3.00", lines));

        // 0.99 * (1 + 2 + ... + 50) = 0.99 * 1275 = 1262.25
        assertThat(result.lines()).hasSize(50);
        assertThat(result.lines()).extracting(PricingResult.Line::productId)
                .containsExactly(IntStream.rangeClosed(1, 50).mapToObj(i -> (long) i).toArray(Long[]::new));
        assertThat(result.subtotal()).isEqualTo(new BigDecimal("1262.25"));
        assertThat(result.total()).isEqualTo(new BigDecimal("1265.25"));
    }

    @Test
    void price_noLines_totalIsDeliveryFee() {
        PricingResult result = calculator.price(input("1.00"));

        assertThat(result.subtotal()).isEqualTo(new BigDecimal("0.00"));
        assertThat(result.total()).isEqualTo(new BigDecimal("1.00"));
    }

    @Test
    void price_negativeUnitPrice_throws() {
        assertThatThrownBy(() -> calculator.price(input("1.00", line(1L, 1, "-1.00"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void price_zeroQuantity_throws() {
        assertThatThrownBy(() -> calculator.price(input("1.00", line(1L, 0, "1.00"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void price_negativeDeliveryFee_throws() {
        assertThatThrownBy(() -> calculator.price(input("-1.00", line(1L, 1, "1.00"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PricingInput input(String fee, PricingInput.Line... lines) {
        return new PricingInput(List.of(lines), new BigDecimal(fee));
    }

    private static PricingInput.Line line(Long productId, int quantity, String unitPrice) {
        return new PricingInput.Line(productId, quantity, new BigDecimal(unitPrice));
    }
}
