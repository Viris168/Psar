package com.psar.order.service.pricing;

import com.psar.order.service.pricing.PricingInput.Offer;
import com.psar.order.service.pricing.PricingInput.OfferLevel;
import com.psar.order.service.pricing.PricingInput.OfferType;
import com.psar.order.service.pricing.PricingResult.AppliedOffer;
import com.psar.order.service.pricing.PricingResult.CouponStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Promotions in {@link PricingCalculator#price}: pure unit tests, no Spring context. */
class PricingCalculatorPromotionTest {

    private static final Instant NOW = Instant.parse("2026-11-28T00:00:00Z");
    private static final Instant IN_30_DAYS = NOW.plus(Duration.ofDays(30));

    private final PricingCalculator calculator = new PricingCalculator();

    // ---------------------------------------------------------------- item offers, each type alone


    @Test
    void price_itemPercentage_discountOnMatchingLine() {
        PricingResult result = price("1.50", List.of(line(1L, 2, "10.00")),
                itemPercentage(10L, "10", null, 1L));

        assertLine(result, 0, "2.00", 0, 10L);
        assertThat(result.lines().get(0).lineTotal()).isEqualTo(amount("20.00"));
        assertThat(result.productDiscount()).isEqualTo(amount("2.00"));
        assertThat(result.total()).isEqualTo(amount("19.50"));
        assertThat(result.appliedOffers()).containsExactly(applied(10L, OfferLevel.ITEM, "2.00"));
        assertThat(result.couponStatus()).isEqualTo(CouponStatus.NONE);
    }

    @Test
    void price_itemPercentage_cappedByMaxDiscountPerLine() {
        PricingResult result = price("0", List.of(line(1L, 10, "10.00"), line(2L, 1, "50.00")),
                itemPercentage(10L, "20", "5.00", 1L, 2L));

        // 100.00 * 20% = 20.00 -> 5.00; 50.00 * 20% = 10.00 -> 5.00 (cap is per line)
        assertLine(result, 0, "5.00", 0, 10L);
        assertLine(result, 1, "5.00", 0, 10L);
        assertThat(result.productDiscount()).isEqualTo(amount("10.00"));
        assertThat(result.appliedOffers()).containsExactly(applied(10L, OfferLevel.ITEM, "10.00"));
        assertThat(result.total()).isEqualTo(amount("140.00"));
    }

    @Test
    void price_itemPercentageBelowCap_capIgnored() {
        PricingResult result = price("0", List.of(line(1L, 1, "10.00")),
                itemPercentage(10L, "20", "5.00", 1L));

        assertLine(result, 0, "2.00", 0, 10L);
    }

    @Test
    void price_itemPercentageHundred_lineFree() {
        PricingResult result = price("1.00", List.of(line(1L, 3, "3.33")),
                itemPercentage(10L, "100", null, 1L));

        assertLine(result, 0, "9.99", 0, 10L);
        assertThat(result.total()).isEqualTo(amount("1.00"));
    }

    @Test
    void price_itemFixed_valueTimesQuantity() {
        PricingResult result = price("0", List.of(line(1L, 4, "3.00")),
                itemFixed(10L, "0.75", 1L));

        assertLine(result, 0, "3.00", 0, 10L);
        assertThat(result.total()).isEqualTo(amount("9.00"));
    }

    @Test
    void price_itemFixedLargerThanLine_lineBecomesZeroNeverNegative() {
        PricingResult result = price("1.50", List.of(line(1L, 2, "3.00"), line(2L, 1, "4.00")),
                itemFixed(10L, "5.00", 1L));

        // 5.00 * 2 = 10.00 off a 6.00 line -> 6.00
        assertLine(result, 0, "6.00", 0, 10L);
        assertLine(result, 1, "0.00", 0, null);
        assertThat(result.productDiscount()).isEqualTo(amount("6.00"));
        assertThat(result.appliedOffers()).containsExactly(applied(10L, OfferLevel.ITEM, "6.00"));
        assertThat(result.total()).isEqualTo(amount("5.50"));
    }

    @ParameterizedTest(name = "buy 2 get 1, {0} units -> {1} free")
    @CsvSource({"1, 0", "2, 0", "3, 1", "4, 1", "5, 1", "6, 2", "7, 2", "9, 3"})
    void price_buyTwoGetOne_freeUnitsPerGroupOfThree(int quantity, int expectedFree) {
        PricingResult result = price("0", List.of(line(1L, quantity, "2.00")),
                buyXGetY(10L, 2, 1, 1L));

        PricingResult.Line line = result.lines().get(0);
        assertThat(line.quantity()).isEqualTo(quantity);
        assertThat(line.freeQuantity()).isEqualTo(expectedFree);
        BigDecimal expectedDiscount = amount("2.00").multiply(BigDecimal.valueOf(expectedFree));
        assertThat(line.discountAmount()).isEqualByComparingTo(expectedDiscount);
        assertThat(line.discountAmount().scale()).isEqualTo(2);
        assertThat(line.appliedOfferId()).isEqualTo(expectedFree == 0 ? null : 10L);
        assertThat(line.lineTotal()).isEqualByComparingTo(amount("2.00").multiply(BigDecimal.valueOf(quantity)));
        assertThat(result.appliedOffers()).hasSize(expectedFree == 0 ? 0 : 1);
    }

    @ParameterizedTest(name = "buy {0} get {1}, {2} units -> {3} free")
    @CsvSource({"1, 1, 1, 0", "1, 1, 2, 1", "1, 1, 5, 2", "1, 2, 3, 2", "1, 2, 5, 2", "1, 2, 6, 4",
            "3, 1, 8, 2"})
    void price_buyXGetY_freeQuantity(int buy, int get, int quantity, int expectedFree) {
        PricingResult result = price("0", List.of(line(1L, quantity, "1.25")), buyXGetY(10L, buy, get, 1L));

        assertThat(result.lines().get(0).freeQuantity()).isEqualTo(expectedFree);
        assertThat(result.lines().get(0).discountAmount())
                .isEqualByComparingTo(amount("1.25").multiply(BigDecimal.valueOf(expectedFree)));
    }

    @Test
    void price_buyXGetY_onlyCountsUnitsOfOneLine() {
        // two products, 2 units each: no line reaches 3 units, so nothing is free
        PricingResult result = price("0", List.of(line(1L, 2, "1.00"), line(2L, 2, "1.00")),
                buyXGetY(10L, 2, 1, 1L, 2L));

        assertThat(result.lines()).allSatisfy(line -> assertThat(line.freeQuantity()).isZero());
        assertThat(result.productDiscount()).isEqualTo(amount("0.00"));
        assertThat(result.appliedOffers()).isEmpty();
    }

    @Test
    void price_itemOfferForOtherProduct_lineUntouched() {
        PricingResult result = price("0", List.of(line(1L, 1, "10.00"), line(2L, 1, "10.00")),
                itemPercentage(10L, "50", null, 2L));

        assertLine(result, 0, "0.00", 0, null);
        assertLine(result, 1, "5.00", 0, 10L);
        assertThat(result.total()).isEqualTo(amount("15.00"));
    }

    @Test
    void price_itemOfferWithZeroValue_notApplied() {
        PricingResult result = price("0", List.of(line(1L, 1, "10.00")), itemPercentage(10L, "0", null, 1L));

        assertLine(result, 0, "0.00", 0, null);
        assertThat(result.appliedOffers()).isEmpty();
    }

    @Test
    void price_freeProduct_offerNotApplied() {
        PricingResult result = price("0", List.of(line(1L, 1, "0.00")), itemFixed(10L, "1.00", 1L));

        assertLine(result, 0, "0.00", 0, null);
        assertThat(result.appliedOffers()).isEmpty();
    }

    @Test
    void price_oneOfferOnSeveralLines_appliedOfferSummed() {
        PricingResult result = price("0",
                List.of(line(1L, 1, "10.00"), line(2L, 2, "5.00"), line(3L, 1, "1.00")),
                itemPercentage(10L, "10", null, 1L, 2L, 3L));

        assertThat(result.lines()).extracting(PricingResult.Line::discountAmount)
                .containsExactly(amount("1.00"), amount("1.00"), amount("0.10"));
        assertThat(result.appliedOffers()).containsExactly(applied(10L, OfferLevel.ITEM, "2.10"));
    }

    @Test
    void price_itemOfferMinimum_checkedAgainstSubtotalBeforeDiscounts() {
        Offer offer = withMinimum(itemPercentage(10L, "50", null, 1L), "20.00");

        PricingResult below = price("0", List.of(line(1L, 1, "19.99")), offer);
        PricingResult equal = price("0", List.of(line(1L, 1, "10.00"), line(2L, 1, "10.00")), offer);

        assertThat(below.productDiscount()).isEqualTo(amount("0.00"));
        assertThat(below.appliedOffers()).isEmpty();
        // the 20.00 includes product 2, which the offer does not cover; only line 1 is discounted
        assertLine(equal, 0, "5.00", 0, 10L);
        assertLine(equal, 1, "0.00", 0, null);
    }

    // ---------------------------------------------------------------- best item offer per line


    @Test
    void price_twoAutomaticOffersOnOneLine_largerWins() {
        PricingResult result = price("0", List.of(line(1L, 3, "4.50")),
                itemPercentage(10L, "10", null, 1L),  // 1.35
                itemFixed(11L, "0.50", 1L));          // 1.50

        assertLine(result, 0, "1.50", 0, 11L);
        assertThat(result.appliedOffers()).containsExactly(applied(11L, OfferLevel.ITEM, "1.50"));
    }

    @Test
    void price_percentageBeatsBuyXGetY_freeQuantityZero() {
        PricingResult result = price("0", List.of(line(1L, 3, "1.00")),
                buyXGetY(10L, 2, 1, 1L),              // 1.00
                itemPercentage(11L, "50", null, 1L)); // 1.50

        assertLine(result, 0, "1.50", 0, 11L);
    }

    @Test
    void price_buyXGetYBeatsPercentage_freeQuantitySet() {
        PricingResult result = price("0", List.of(line(1L, 3, "1.00")),
                buyXGetY(10L, 2, 1, 1L),              // 1.00
                itemPercentage(11L, "20", null, 1L)); // 0.60

        assertLine(result, 0, "1.00", 1, 10L);
    }

    @Test
    void price_cappedPercentageLosesToSmallerUncappedValue() {
        PricingResult result = price("0", List.of(line(1L, 1, "100.00")),
                itemPercentage(10L, "50", "3.00", 1L), // 50.00 -> 3.00
                itemFixed(11L, "4.00", 1L));           // 4.00

        assertLine(result, 0, "4.00", 0, 11L);
    }

    @Test
    void price_equalDiscounts_offerThatEndsFirstWins() {
        Offer later = withEnd(itemFixed(10L, "1.00", 1L), NOW.plus(Duration.ofDays(10)));
        Offer sooner = withEnd(itemPercentage(11L, "10", null, 1L), NOW.plus(Duration.ofDays(2)));

        PricingResult result = price("0", List.of(line(1L, 1, "10.00")), later, sooner);

        assertLine(result, 0, "1.00", 0, 11L);
    }

    @Test
    void price_equalDiscountsAndEnd_lowestIdWinsWhateverTheInputOrder() {
        Offer high = itemFixed(12L, "1.00", 1L);
        Offer low = itemPercentage(11L, "10", null, 1L);

        assertLine(price("0", List.of(line(1L, 1, "10.00")), high, low), 0, "1.00", 0, 11L);
        assertLine(price("0", List.of(line(1L, 1, "10.00")), low, high), 0, "1.00", 0, 11L);
    }

    @Test
    void price_differentLines_eachGetsItsOwnBestOffer() {
        PricingResult result = price("0",
                List.of(line(1L, 1, "10.00"), line(2L, 3, "1.00")),
                itemPercentage(10L, "10", null, 1L, 2L), // 1.00 / 0.30
                buyXGetY(11L, 2, 1, 1L, 2L));            // 0 / 1.00

        assertLine(result, 0, "1.00", 0, 10L);
        assertLine(result, 1, "1.00", 1, 11L);
        assertThat(result.appliedOffers()).containsExactly(
                applied(10L, OfferLevel.ITEM, "1.00"), applied(11L, OfferLevel.ITEM, "1.00"));
    }

    @Test
    void price_offersShuffled_sameResult() {
        List<Offer> offers = new ArrayList<>(List.of(
                itemPercentage(10L, "10", null, 1L, 2L, 3L),
                itemFixed(11L, "1.00", 1L, 2L),
                buyXGetY(12L, 1, 1, 3L),
                orderPercentage(20L, "5", null, null),
                orderFixed(21L, "1.00", null),
                freeDelivery(22L, null)));
        List<PricingInput.Line> lines = List.of(line(1L, 2, "10.00"), line(2L, 1, "5.00"), line(3L, 4, "2.00"));
        PricingResult expected = calculator.price(new PricingInput(lines, amount("1.00"), offers));

        for (long seed = 0; seed < 20; seed++) {
            Collections.shuffle(offers, new java.util.Random(seed));
            PricingResult shuffled = calculator.price(new PricingInput(lines, amount("1.00"), offers));
            assertThat(shuffled.lines()).isEqualTo(expected.lines());
            assertThat(shuffled.total()).isEqualTo(expected.total());
            assertThat(shuffled.orderDiscount()).isEqualTo(expected.orderDiscount());
            assertThat(shuffled.deliveryFee()).isEqualTo(expected.deliveryFee());
            assertThat(shuffled.appliedOffers()).containsExactlyInAnyOrderElementsOf(expected.appliedOffers());
        }
    }

    // ---------------------------------------------------------------- order slot


    @Test
    void price_orderPercentage_onSubtotalAfterItemDiscounts() {
        PricingResult result = price("1.50", List.of(line(1L, 4, "10.00")),
                itemPercentage(10L, "10", null, 1L),       // 4.00
                orderPercentage(20L, "10", null, null));   // 36.00 * 10% = 3.60

        assertThat(result.productDiscount()).isEqualTo(amount("4.00"));
        assertThat(result.orderDiscount()).isEqualTo(amount("3.60"));
        assertThat(result.total()).isEqualTo(amount("33.90"));
        assertThat(result.appliedOffers()).containsExactly(
                applied(10L, OfferLevel.ITEM, "4.00"), applied(20L, OfferLevel.ORDER, "3.60"));
    }

    @Test
    void price_orderPercentage_cappedByMaxDiscount() {
        PricingResult result = price("0", List.of(line(1L, 1, "200.00")),
                orderPercentage(20L, "10", "15.00", null));

        assertThat(result.orderDiscount()).isEqualTo(amount("15.00"));
        assertThat(result.total()).isEqualTo(amount("185.00"));
    }

    @Test
    void price_orderFixed_takenOnce() {
        PricingResult result = price("1.00", List.of(line(1L, 3, "10.00"), line(2L, 2, "5.00")),
                orderFixed(20L, "5.00", null));

        assertThat(result.orderDiscount()).isEqualTo(amount("5.00"));
        assertThat(result.total()).isEqualTo(amount("36.00"));
    }

    @Test
    void price_orderFixedLargerThanRemaining_cappedAtRemaining() {
        PricingResult result = price("2.00", List.of(line(1L, 1, "3.00")), orderFixed(20L, "10.00", null));

        assertThat(result.orderDiscount()).isEqualTo(amount("3.00"));
        assertThat(result.appliedOffers()).containsExactly(applied(20L, OfferLevel.ORDER, "3.00"));
        assertThat(result.total()).isEqualTo(amount("2.00"));
    }

    @Test
    void price_twoAutomaticOrderOffers_largerWinsOnlyOneApplies() {
        PricingResult result = price("0", List.of(line(1L, 4, "10.00")),
                orderPercentage(20L, "5", null, null), // 2.00
                orderFixed(21L, "3.00", null));        // 3.00

        assertThat(result.orderDiscount()).isEqualTo(amount("3.00"));
        assertThat(result.appliedOffers()).containsExactly(applied(21L, OfferLevel.ORDER, "3.00"));
    }

    @Test
    void price_equalAutomaticOrderOffers_endsFirstThenLowestId() {
        Offer a = withEnd(orderFixed(23L, "2.00", null), NOW.plus(Duration.ofDays(3)));
        Offer b = withEnd(orderPercentage(21L, "5", null, null), NOW.plus(Duration.ofDays(9)));
        Offer c = withEnd(orderFixed(22L, "2.00", null), NOW.plus(Duration.ofDays(3)));

        PricingResult result = price("0", List.of(line(1L, 4, "10.00")), b, a, c);

        assertThat(result.appliedOffers()).containsExactly(applied(22L, OfferLevel.ORDER, "2.00"));
    }

    @ParameterizedTest(name = "subtotal after items {0}, minimum 20.00 -> applied {1}")
    @CsvSource({"19.99, false", "20.00, true", "20.01, true"})
    void price_orderMinimum_boundary(String afterItems, boolean applied) {
        PricingResult result = price("0", List.of(line(1L, 1, afterItems)),
                orderFixed(20L, "5.00", "20.00"));

        assertThat(result.orderDiscount()).isEqualTo(amount(applied ? "5.00" : "0.00"));
        assertThat(result.appliedOffers()).hasSize(applied ? 1 : 0);
    }

    @ParameterizedTest(name = "subtotal 25.00, item discount {0} -> order offer applied {1}")
    @CsvSource({"5.01, false", "5.00, true", "4.99, true"})
    void price_orderMinimum_checkedAfterItemDiscounts(String itemDiscount, boolean applied) {
        PricingResult result = price("0", List.of(line(1L, 1, "25.00")),
                itemFixed(10L, itemDiscount, 1L),
                orderFixed(20L, "5.00", "20.00"));

        assertThat(result.subtotal()).isEqualTo(amount("25.00"));
        assertThat(result.productDiscount()).isEqualTo(amount(itemDiscount));
        assertThat(result.orderDiscount()).isEqualTo(amount(applied ? "5.00" : "0.00"));
    }

    @Test
    void price_largerOrderOfferBelowMinimum_smallerOneApplies() {
        PricingResult result = price("0", List.of(line(1L, 1, "25.00")),
                orderFixed(20L, "10.00", "30.00"),
                orderFixed(21L, "2.00", null));

        assertThat(result.appliedOffers()).containsExactly(applied(21L, OfferLevel.ORDER, "2.00"));
    }

    // ---------------------------------------------------------------- coupons


    @Test
    void price_orderCoupon_beatsLargerAutomaticOrderOffer() {
        PricingResult result = price("1.50", List.of(line(1L, 4, "10.00")),
                orderFixed(20L, "8.00", null),
                coupon(orderFixed(30L, "2.00", null)));

        assertThat(result.couponStatus()).isEqualTo(CouponStatus.APPLIED);
        assertThat(result.orderDiscount()).isEqualTo(amount("2.00"));
        assertThat(result.appliedOffers()).containsExactly(applied(30L, OfferLevel.ORDER, "2.00"));
        assertThat(result.total()).isEqualTo(amount("39.50"));
    }

    @Test
    void price_orderCoupon_beatsAutomaticFreeDelivery() {
        PricingResult result = price("5.00", List.of(line(1L, 4, "10.00")),
                freeDelivery(22L, null),
                coupon(orderFixed(30L, "1.00", null)));

        assertThat(result.deliveryFee()).isEqualTo(amount("5.00"));
        assertThat(result.orderDiscount()).isEqualTo(amount("1.00"));
        assertThat(result.appliedOffers()).containsExactly(applied(30L, OfferLevel.ORDER, "1.00"));
        assertThat(result.total()).isEqualTo(amount("44.00"));
    }

    @Test
    void price_orderCoupon_itemOffersStillApply() {
        PricingResult result = price("0", List.of(line(1L, 2, "10.00")),
                itemPercentage(10L, "10", null, 1L),
                coupon(orderPercentage(30L, "50", null, null)));

        // 20.00 - 2.00 = 18.00; 50% = 9.00
        assertThat(result.productDiscount()).isEqualTo(amount("2.00"));
        assertThat(result.orderDiscount()).isEqualTo(amount("9.00"));
        assertThat(result.total()).isEqualTo(amount("9.00"));
        assertThat(result.appliedOffers()).containsExactly(
                applied(10L, OfferLevel.ITEM, "2.00"), applied(30L, OfferLevel.ORDER, "9.00"));
    }

    @Test
    void price_orderCouponBelowMinimum_belowMinimumAndAutomaticOfferApplies() {
        PricingResult result = price("0", List.of(line(1L, 1, "15.00")),
                orderFixed(20L, "1.00", null),
                coupon(orderFixed(30L, "5.00", "20.00")));

        assertThat(result.couponStatus()).isEqualTo(CouponStatus.BELOW_MINIMUM);
        assertThat(result.appliedOffers()).containsExactly(applied(20L, OfferLevel.ORDER, "1.00"));
        assertThat(result.total()).isEqualTo(amount("14.00"));
    }

    @Test
    void price_orderCouponMinimumAfterItemDiscounts_belowMinimum() {
        PricingResult result = price("0", List.of(line(1L, 1, "22.00")),
                itemFixed(10L, "2.01", 1L),
                coupon(orderFixed(30L, "5.00", "20.00")));

        assertThat(result.couponStatus()).isEqualTo(CouponStatus.BELOW_MINIMUM);
        assertThat(result.orderDiscount()).isEqualTo(amount("0.00"));
    }

    @Test
    void price_orderCouponTakingNothingOff_stillAppliedAndListed() {
        // everything is already free, so the coupon has nothing left to take, but it was used
        PricingResult result = price("1.00", List.of(line(1L, 1, "5.00")),
                itemFixed(10L, "5.00", 1L),
                coupon(orderFixed(30L, "3.00", null)));

        assertThat(result.couponStatus()).isEqualTo(CouponStatus.APPLIED);
        assertThat(result.orderDiscount()).isEqualTo(amount("0.00"));
        assertThat(result.appliedOffers()).contains(applied(30L, OfferLevel.ORDER, "0.00"));
        assertThat(result.total()).isEqualTo(amount("1.00"));
    }

    @Test
    void price_itemCoupon_replacesAutomaticOnLinesWhereItGivesMore() {
        PricingResult result = price("0",
                List.of(line(1L, 1, "10.00"), line(2L, 1, "10.00"), line(3L, 1, "10.00")),
                itemPercentage(10L, "20", null, 1L, 2L),          // 2.00 on 1 and 2
                coupon(itemFixed(30L, "1.50", 2L, 3L)),            // 1.50 on 2 and 3
                itemFixed(11L, "3.00", 1L));                      // 3.00 on 1

        assertLine(result, 0, "3.00", 0, 11L);  // automatic 3.00 (coupon doesn't cover it)
        assertLine(result, 1, "2.00", 0, 10L);  // automatic 2.00 beats the coupon's 1.50
        assertLine(result, 2, "1.50", 0, 30L);  // coupon beats nothing
        assertThat(result.couponStatus()).isEqualTo(CouponStatus.APPLIED);
        assertThat(result.appliedOffers()).containsExactly(
                applied(11L, OfferLevel.ITEM, "3.00"),
                applied(10L, OfferLevel.ITEM, "2.00"),
                applied(30L, OfferLevel.ITEM, "1.50"));
    }

    @Test
    void price_appliedItemCoupon_holdsOrderSlot() {
        PricingResult result = price("3.00", List.of(line(1L, 2, "10.00")),
                coupon(itemPercentage(30L, "10", null, 1L)),
                orderFixed(20L, "5.00", null),
                freeDelivery(21L, null));

        assertThat(result.couponStatus()).isEqualTo(CouponStatus.APPLIED);
        assertThat(result.orderDiscount()).isEqualTo(amount("0.00"));
        assertThat(result.deliveryFee()).isEqualTo(amount("3.00"));
        assertThat(result.appliedOffers()).containsExactly(applied(30L, OfferLevel.ITEM, "2.00"));
        assertThat(result.total()).isEqualTo(amount("21.00"));
    }

    @Test
    void price_itemCouponEqualToAutomatic_notBetterAutomaticKept() {
        PricingResult result = price("0", List.of(line(1L, 1, "10.00")),
                itemPercentage(10L, "10", null, 1L),
                coupon(itemFixed(30L, "1.00", 1L)));

        assertThat(result.couponStatus()).isEqualTo(CouponStatus.NOT_BETTER);
        assertLine(result, 0, "1.00", 0, 10L);
    }

    @Test
    void price_itemCouponNotBetter_automaticOrderOfferApplies() {
        PricingResult result = price("0", List.of(line(1L, 1, "10.00")),
                itemPercentage(10L, "50", null, 1L),
                coupon(itemFixed(30L, "1.00", 1L)),
                orderFixed(20L, "1.00", null));

        assertThat(result.couponStatus()).isEqualTo(CouponStatus.NOT_BETTER);
        assertThat(result.appliedOffers()).containsExactly(
                applied(10L, OfferLevel.ITEM, "5.00"), applied(20L, OfferLevel.ORDER, "1.00"));
        assertThat(result.total()).isEqualTo(amount("4.00"));
    }

    @Test
    void price_itemCouponTakingNothingOff_notBetter() {
        // buy 2 get 1 coupon, but only 2 units: nothing free
        PricingResult result = price("0", List.of(line(1L, 2, "10.00")),
                coupon(buyXGetY(30L, 2, 1, 1L)));

        assertThat(result.couponStatus()).isEqualTo(CouponStatus.NOT_BETTER);
        assertThat(result.appliedOffers()).isEmpty();
        assertThat(result.total()).isEqualTo(amount("20.00"));
    }

    @Test
    void price_itemCouponBuyXGetY_setsFreeQuantity() {
        PricingResult result = price("0", List.of(line(1L, 6, "2.00")),
                itemPercentage(10L, "10", null, 1L),  // 1.20
                coupon(buyXGetY(30L, 2, 1, 1L)));     // 2 free = 4.00

        assertThat(result.couponStatus()).isEqualTo(CouponStatus.APPLIED);
        assertLine(result, 0, "4.00", 2, 30L);
    }

    @Test
    void price_itemCouponMinimum_checkedAgainstSubtotalBeforeDiscounts() {
        Offer couponOffer = coupon(withMinimum(itemPercentage(30L, "50", null, 1L), "20.00"));

        PricingResult below = price("0", List.of(line(1L, 1, "19.99")), couponOffer);
        // the item discount of 10.00 would put the remainder below 20, but the item minimum is before discounts
        PricingResult equal = price("0", List.of(line(1L, 1, "20.00")), couponOffer);

        assertThat(below.couponStatus()).isEqualTo(CouponStatus.BELOW_MINIMUM);
        assertThat(below.productDiscount()).isEqualTo(amount("0.00"));
        assertThat(equal.couponStatus()).isEqualTo(CouponStatus.APPLIED);
        assertThat(equal.productDiscount()).isEqualTo(amount("10.00"));
    }

    @Test
    void price_itemCouponBelowMinimum_automaticOffersApply() {
        PricingResult result = price("0", List.of(line(1L, 1, "10.00")),
                itemPercentage(10L, "10", null, 1L),
                coupon(withMinimum(itemPercentage(30L, "50", null, 1L), "50.00")),
                orderFixed(20L, "1.00", null));

        assertThat(result.couponStatus()).isEqualTo(CouponStatus.BELOW_MINIMUM);
        assertThat(result.appliedOffers()).containsExactly(
                applied(10L, OfferLevel.ITEM, "1.00"), applied(20L, OfferLevel.ORDER, "1.00"));
    }

    @Test
    void price_noCoupon_statusNone() {
        PricingResult result = price("0", List.of(line(1L, 1, "10.00")),
                itemPercentage(10L, "10", null, 1L), orderFixed(20L, "1.00", null));

        assertThat(result.couponStatus()).isEqualTo(CouponStatus.NONE);
    }

    @Test
    void price_twoCoupons_rejectedByInput() {
        assertThatThrownBy(() -> new PricingInput(List.of(line(1L, 1, "1.00")), amount("0"),
                List.of(coupon(orderFixed(30L, "1.00", null)), coupon(orderFixed(31L, "1.00", null)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- free delivery


    @Test
    void price_freeDeliveryWithFee_feeBecomesZeroListedWithWaivedFee() {
        PricingResult result = price("2.50", List.of(line(1L, 3, "10.00")), freeDelivery(22L, "25.00"));

        assertThat(result.deliveryFee()).isEqualTo(amount("0.00"));
        assertThat(result.orderDiscount()).isEqualTo(amount("0.00"));
        assertThat(result.appliedOffers()).containsExactly(applied(22L, OfferLevel.ORDER, "2.50"));
        assertThat(result.total()).isEqualTo(amount("30.00"));
    }

    @Test
    void price_freeDeliveryWithZeroFee_notApplied() {
        PricingResult result = price("0", List.of(line(1L, 3, "10.00")), freeDelivery(22L, null));

        assertThat(result.deliveryFee()).isEqualTo(amount("0.00"));
        assertThat(result.appliedOffers()).isEmpty();
        assertThat(result.total()).isEqualTo(amount("30.00"));
    }

    @Test
    void price_freeDeliveryWithZeroFee_otherOrderOfferApplies() {
        PricingResult result = price("0", List.of(line(1L, 3, "10.00")),
                freeDelivery(22L, null), orderFixed(20L, "0.50", null));

        assertThat(result.appliedOffers()).containsExactly(applied(20L, OfferLevel.ORDER, "0.50"));
    }

    @Test
    void price_freeDeliveryBelowMinimum_feeKept() {
        PricingResult result = price("2.50", List.of(line(1L, 1, "24.99")), freeDelivery(22L, "25.00"));

        assertThat(result.deliveryFee()).isEqualTo(amount("2.50"));
        assertThat(result.appliedOffers()).isEmpty();
        assertThat(result.total()).isEqualTo(amount("27.49"));
    }

    @Test
    void price_freeDeliveryMinimum_afterItemDiscounts() {
        PricingResult result = price("2.50", List.of(line(1L, 3, "10.00")),
                itemPercentage(10L, "20", null, 1L),   // 30.00 -> 24.00
                freeDelivery(22L, "25.00"));

        assertThat(result.deliveryFee()).isEqualTo(amount("2.50"));
        assertThat(result.total()).isEqualTo(amount("26.50"));
    }

    @Test
    void price_freeDeliveryVsPercentage_largerSavingWins() {
        PricingResult feeWins = price("5.00", List.of(line(1L, 1, "40.00")),
                freeDelivery(22L, null), orderPercentage(20L, "10", null, null));   // 5.00 vs 4.00
        PricingResult percentWins = price("3.00", List.of(line(1L, 1, "40.00")),
                freeDelivery(22L, null), orderPercentage(20L, "10", null, null));   // 3.00 vs 4.00

        assertThat(feeWins.appliedOffers()).containsExactly(applied(22L, OfferLevel.ORDER, "5.00"));
        assertThat(feeWins.total()).isEqualTo(amount("40.00"));
        assertThat(percentWins.appliedOffers()).containsExactly(applied(20L, OfferLevel.ORDER, "4.00"));
        assertThat(percentWins.total()).isEqualTo(amount("39.00"));
    }

    @Test
    void price_freeDeliveryCouponWithZeroFee_appliedWithZeroDiscount() {
        PricingResult result = price("0", List.of(line(1L, 1, "10.00")),
                coupon(freeDelivery(33L, null)), orderFixed(20L, "1.00", null));

        assertThat(result.couponStatus()).isEqualTo(CouponStatus.APPLIED);
        assertThat(result.appliedOffers()).containsExactly(applied(33L, OfferLevel.ORDER, "0.00"));
        assertThat(result.total()).isEqualTo(amount("10.00"));
    }

    @Test
    void price_freeDeliveryCouponWithFee_feeWaived() {
        PricingResult result = price("1.75", List.of(line(1L, 1, "10.00")), coupon(freeDelivery(33L, null)));

        assertThat(result.couponStatus()).isEqualTo(CouponStatus.APPLIED);
        assertThat(result.deliveryFee()).isEqualTo(amount("0.00"));
        assertThat(result.appliedOffers()).containsExactly(applied(33L, OfferLevel.ORDER, "1.75"));
        assertThat(result.total()).isEqualTo(amount("10.00"));
    }

    @Test
    void price_freeDeliveryCouponBelowMinimum_feeKept() {
        PricingResult result = price("1.75", List.of(line(1L, 1, "10.00")),
                coupon(freeDelivery(33L, "30.00")));

        assertThat(result.couponStatus()).isEqualTo(CouponStatus.BELOW_MINIMUM);
        assertThat(result.deliveryFee()).isEqualTo(amount("1.75"));
        assertThat(result.total()).isEqualTo(amount("11.75"));
    }

    // ---------------------------------------------------------------- never negative, rounding


    @Test
    void price_hugeDiscounts_totalZeroNeverNegative() {
        PricingResult result = price("0", List.of(line(1L, 2, "10.00"), line(2L, 1, "5.00")),
                itemFixed(10L, "1000.00", 1L, 2L),
                coupon(orderFixed(30L, "1000.00", null)));

        assertThat(result.productDiscount()).isEqualTo(amount("25.00"));
        assertThat(result.orderDiscount()).isEqualTo(amount("0.00"));
        assertThat(result.total()).isEqualTo(amount("0.00"));
        assertThat(result.lines()).allSatisfy(line ->
                assertThat(line.discountAmount()).isLessThanOrEqualTo(line.lineTotal()));
    }

    @Test
    void price_hugeOrderDiscount_onlyDeliveryFeeLeft() {
        PricingResult result = price("1.50", List.of(line(1L, 1, "10.00")),
                orderFixed(20L, "500.00", null));

        assertThat(result.orderDiscount()).isEqualTo(amount("10.00"));
        assertThat(result.total()).isEqualTo(amount("1.50"));
    }

    @Test
    void price_hundredPercentOrderOffer_totalIsFee() {
        PricingResult result = price("1.00", List.of(line(1L, 3, "7.77")),
                orderPercentage(20L, "100", null, null));

        assertThat(result.orderDiscount()).isEqualTo(amount("23.31"));
        assertThat(result.total()).isEqualTo(amount("1.00"));
    }

    @Test
    void price_percentageRoundedPerLineHalfUp() {
        // 0.10 * 5% = 0.005 -> 0.01 per line, so 0.03 in all (on the 0.30 sum it would be 0.015 -> 0.02)
        PricingResult result = price("0",
                List.of(line(1L, 1, "0.10"), line(2L, 1, "0.10"), line(3L, 1, "0.10")),
                itemPercentage(10L, "5", null, 1L, 2L, 3L));

        assertThat(result.lines()).extracting(PricingResult.Line::discountAmount)
                .containsOnly(amount("0.01"));
        assertThat(result.productDiscount()).isEqualTo(amount("0.03"));
        assertThat(result.appliedOffers()).containsExactly(applied(10L, OfferLevel.ITEM, "0.03"));
        assertThat(result.total()).isEqualTo(amount("0.27"));
    }

    @Test
    void price_manyLinesOddPercentage_eachLineRoundedHalfUp() {
        List<PricingInput.Line> lines = new ArrayList<>();
        Long[] ids = new Long[30];
        for (int i = 0; i < 30; i++) {
            ids[i] = (long) (i + 1);
            lines.add(line(ids[i], 1, "0.99"));
        }
        // 0.99 * 12.5% = 0.12375 -> 0.12 per line; 30 lines -> 3.60 (on the 29.70 sum it would be 3.71)
        PricingResult result = price("0", lines, itemPercentage(10L, "12.5", null, ids));

        assertThat(result.lines()).extracting(PricingResult.Line::discountAmount).containsOnly(amount("0.12"));
        assertThat(result.subtotal()).isEqualTo(amount("29.70"));
        assertThat(result.productDiscount()).isEqualTo(amount("3.60"));
        assertThat(result.total()).isEqualTo(amount("26.10"));
    }

    @Test
    void price_orderPercentageHalfUp() {
        // 26.10 * 5% = 1.305 -> 1.31
        PricingResult result = price("0", List.of(line(1L, 1, "26.10")), orderPercentage(20L, "5", null, null));

        assertThat(result.orderDiscount()).isEqualTo(amount("1.31"));
        assertThat(result.total()).isEqualTo(amount("24.79"));
    }

    @Test
    void price_withPromotions_everyAmountScaleTwo() {
        PricingResult result = price("1.5", List.of(line(1L, 3, "1.1"), line(2L, 7, "0.3")),
                itemPercentage(10L, "33.3333", "1.5", 1L),
                buyXGetY(11L, 2, 1, 2L),
                orderPercentage(20L, "7.5", null, null));

        List<BigDecimal> amounts = new ArrayList<>(List.of(result.subtotal(), result.productDiscount(),
                result.orderDiscount(), result.rewardDiscount(), result.deliveryFee(), result.total()));
        result.lines().forEach(line -> amounts.addAll(List.of(line.unitPrice(), line.discountAmount(),
                line.lineTotal())));
        result.appliedOffers().forEach(offer -> amounts.add(offer.discountAmount()));
        assertThat(amounts).allSatisfy(amount -> assertThat(amount.scale()).isEqualTo(2));
        // line 1: 3.30 * 33.3333% = 1.0999989 -> 1.10; line 2: 2 free * 0.30 = 0.60
        // subtotal 5.40, after items 3.70, 7.5% = 0.2775 -> 0.28; total 3.70 - 0.28 + 1.50 = 4.92
        assertThat(result.productDiscount()).isEqualTo(amount("1.70"));
        assertThat(result.orderDiscount()).isEqualTo(amount("0.28"));
        assertThat(result.total()).isEqualTo(amount("4.92"));
    }

    @Test
    void price_rewardDiscountStaysZero() {
        PricingResult result = price("0", List.of(line(1L, 1, "10.00")), itemFixed(10L, "1.00", 1L));

        assertThat(result.rewardDiscount()).isEqualTo(amount("0.00"));
    }

    // ---------------------------------------------------------------- malformed offers


    @Test
    void price_buyXGetYAtOrderLevel_throws() {
        Offer offer = new Offer(1L, "bad", OfferType.BUY_X_GET_Y, OfferLevel.ORDER, null, null, 2, 1, null,
                Set.of(), false, IN_30_DAYS);

        assertThatThrownBy(() -> price("0", List.of(line(1L, 1, "1.00")), offer))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void price_buyXGetYWithoutQuantities_throws() {
        Offer offer = new Offer(1L, "bad", OfferType.BUY_X_GET_Y, OfferLevel.ITEM, null, null, 0, 1, null,
                Set.of(1L), false, IN_30_DAYS);

        assertThatThrownBy(() -> price("0", List.of(line(1L, 1, "1.00")), offer))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void price_freeDeliveryAtItemLevel_throws() {
        Offer offer = new Offer(1L, "bad", OfferType.FREE_DELIVERY, OfferLevel.ITEM, null, null, null, null,
                null, Set.of(1L), false, IN_30_DAYS);

        assertThatThrownBy(() -> price("0", List.of(line(1L, 1, "1.00")), offer))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void price_percentageWithoutValueOrOverHundred_throws() {
        Offer noValue = new Offer(1L, "bad", OfferType.PERCENTAGE, OfferLevel.ORDER, null, null, null, null,
                null, Set.of(), false, IN_30_DAYS);
        Offer over = new Offer(2L, "bad", OfferType.PERCENTAGE, OfferLevel.ORDER, amount("100.01"), null, null,
                null, null, Set.of(), false, IN_30_DAYS);

        assertThatThrownBy(() -> price("0", List.of(line(1L, 1, "1.00")), noValue))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> price("0", List.of(line(1L, 1, "1.00")), over))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void price_fixedWithoutValue_throws() {
        Offer offer = new Offer(1L, "bad", OfferType.FIXED_AMOUNT, OfferLevel.ITEM, null, null, null, null,
                null, Set.of(1L), false, IN_30_DAYS);

        assertThatThrownBy(() -> price("0", List.of(line(1L, 1, "1.00")), offer))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- table of hand-calculated carts

    /*
     * Products: 1 rice 10.00, 2 oil 4.50, 3 noodles 0.99, 4 soap 2.35.
     * Offers (all end in 30 days unless noted):
     *   P10    10 %         ITEM  on rice, oil              (ends in 10 days)
     *   P25C   25 % max 2   ITEM  on rice                   (ends in 5 days)
     *   F050   0.50 / unit  ITEM  on oil, noodles
     *   B2G1   buy 2 get 1  ITEM  on noodles
     *   B1G1   buy 1 get 1  ITEM  on soap
     *   P15    15 %         ITEM  on noodles, soap
     *   HUGE   50.00 / unit ITEM  on rice
     *   O5     5 %          ORDER min 20
     *   OF3    3.00         ORDER min 30
     *   FD     free delivery ORDER min 25
     *   C2     coupon 2.00              ORDER
     *   C15    coupon 15 % max 5 min 10 ORDER
     *   CI30   coupon 30 %              ITEM on oil
     *   CI010  coupon 0.10 / unit       ITEM on oil
     *   CFD    coupon free delivery     ORDER
     */
    private static final Map<Long, String> PRICES = Map.of(1L, "10.00", 2L, "4.50", 3L, "0.99", 4L, "2.35");

    private static final Map<String, Offer> OFFERS = Map.ofEntries(
            Map.entry("P10", withEnd(itemPercentage(10L, "10", null, 1L, 2L), NOW.plus(Duration.ofDays(10)))),
            Map.entry("P25C", withEnd(itemPercentage(11L, "25", "2.00", 1L), NOW.plus(Duration.ofDays(5)))),
            Map.entry("F050", itemFixed(12L, "0.50", 2L, 3L)),
            Map.entry("B2G1", buyXGetY(13L, 2, 1, 3L)),
            Map.entry("B1G1", buyXGetY(14L, 1, 1, 4L)),
            Map.entry("P15", itemPercentage(15L, "15", null, 3L, 4L)),
            Map.entry("HUGE", itemFixed(16L, "50.00", 1L)),
            Map.entry("O5", orderPercentage(20L, "5", null, "20.00")),
            Map.entry("OF3", orderFixed(21L, "3.00", "30.00")),
            Map.entry("FD", freeDelivery(22L, "25.00")),
            Map.entry("C2", coupon(orderFixed(30L, "2.00", null))),
            Map.entry("C15", coupon(orderPercentage(31L, "15", "5.00", "10.00"))),
            Map.entry("CI30", coupon(itemPercentage(32L, "30", null, 2L))),
            Map.entry("CI010", coupon(itemFixed(34L, "0.10", 2L))),
            Map.entry("CFD", coupon(freeDelivery(33L, null))));

    static Stream<Arguments> carts() {
        return Stream.of(
                // name, lines (product x qty), offers, fee, subtotal, product disc, order disc, fee after, total, coupon
                cart("01 no offers", "1x2", "", "1.50", "20.00", "0.00", "0.00", "1.50", "21.50", "NONE"),
                // 20.00 * 10% = 2.00
                cart("02 item 10%", "1x2", "P10", "1.50", "20.00", "2.00", "0.00", "1.50", "19.50", "NONE"),
                // P10 1.00 vs P25C 2.50 capped 2.00 -> 2.00
                cart("03 capped percent still larger", "1x1", "P10 P25C", "1.50",
                        "10.00", "2.00", "0.00", "1.50", "9.50", "NONE"),
                // 13.50: P10 1.35 vs F050 1.50 -> 1.50
                cart("04 fixed beats percent", "2x3", "P10 F050", "1.50",
                        "13.50", "1.50", "0.00", "1.50", "13.50", "NONE"),
                // 5.94: F050 3.00 vs B2G1 2 free 1.98 -> 3.00
                cart("05 fixed beats bxgy", "3x6", "F050 B2G1", "1.50",
                        "5.94", "3.00", "0.00", "1.50", "4.44", "NONE"),
                // 5 units, 1 free = 0.99
                cart("06 bxgy 5 units", "3x5", "B2G1", "1.50", "4.95", "0.99", "0.00", "1.50", "5.46", "NONE"),
                // 3 units buy 1 get 1: 1 free = 2.35
                cart("07 b1g1 3 units", "4x3", "B1G1", "1.50", "7.05", "2.35", "0.00", "1.50", "6.20", "NONE"),
                // 20.00 meets min 20: 5% = 1.00
                cart("08 order 5% at minimum", "1x2", "O5", "1.50",
                        "20.00", "0.00", "1.00", "1.50", "20.50", "NONE"),
                // after items 18.00 < 20 -> no order offer
                cart("09 item discount drops below min", "1x2", "P10 O5", "1.50",
                        "20.00", "2.00", "0.00", "1.50", "19.50", "NONE"),
                // 40.00: O5 2.00 vs OF3 3.00 -> 3.00
                cart("10 best order offer", "1x4", "O5 OF3", "1.50",
                        "40.00", "0.00", "3.00", "1.50", "38.50", "NONE"),
                // 30.00, fee 5: O5 1.50, OF3 3.00, FD 5.00 -> free delivery
                cart("11 free delivery wins", "1x3", "O5 OF3 FD", "5.00",
                        "30.00", "0.00", "0.00", "0.00", "30.00", "NONE"),
                // coupon 2.00 beats automatic 3.00 (customer's choice)
                cart("12 coupon beats automatic", "1x4", "OF3 C2", "1.50",
                        "40.00", "0.00", "2.00", "1.50", "39.50", "APPLIED"),
                // 9.00 < 10
                cart("13 coupon below minimum", "2x2", "C15", "1.50",
                        "9.00", "0.00", "0.00", "1.50", "10.50", "BELOW_MINIMUM"),
                // 50.00 * 15% = 7.50 capped 5.00
                cart("14 coupon capped", "1x5", "C15", "1.50", "50.00", "0.00", "5.00", "1.50", "46.50", "APPLIED"),
                // 10.00 - 1.00 = 9.00 < 10
                cart("15 coupon below min after items", "1x1", "P10 C15", "1.50",
                        "10.00", "1.00", "0.00", "1.50", "10.50", "BELOW_MINIMUM"),
                // rice P10 2.00; oil P10 0.90 vs CI30 2.70 -> 2.70; coupon holds the slot so O5 is skipped
                cart("16 item coupon applied", "1x2 2x2", "P10 O5 CI30", "1.50",
                        "29.00", "4.70", "0.00", "1.50", "25.80", "APPLIED"),
                // oil P10 0.90 vs CI010 0.20 -> not better; after items 26.10, O5 1.305 -> 1.31
                cart("17 item coupon not better", "1x2 2x2", "P10 O5 CI010", "1.50",
                        "29.00", "2.90", "1.31", "1.50", "26.29", "NOT_BETTER"),
                // free delivery coupon on a free delivery
                cart("18 free delivery coupon, fee 0", "1x1", "CFD", "0",
                        "10.00", "0.00", "0.00", "0.00", "10.00", "APPLIED"),
                // 50.00 * 2 off a 20.00 line -> 20.00; coupon has nothing left
                cart("19 huge discount", "1x2", "HUGE C2", "0",
                        "20.00", "20.00", "0.00", "0.00", "0.00", "APPLIED"),
                // 0.99 * 15% = 0.1485 -> 0.15; 2.35 * 15% = 0.3525 -> 0.35
                cart("20 rounding per line", "3x1 4x1", "P15", "1.50",
                        "3.34", "0.50", "0.00", "1.50", "4.34", "NONE"),
                // 5.94 - 1.98 = 3.96; - 2.00 = 1.96
                cart("21 bxgy and coupon", "3x6", "B2G1 C2", "1.50",
                        "5.94", "1.98", "2.00", "1.50", "3.46", "APPLIED"),
                // 24.50 < 25
                cart("22 free delivery below minimum", "1x2 2x1", "FD", "2.00",
                        "24.50", "0.00", "0.00", "2.00", "26.50", "NONE"),
                // rice 30.00: P10 3.00 vs P25C 2.00 -> 3.00; oil 13.50: F050 1.50; noodles 5.94: F050 3.00;
                // soap 9.40: B1G1 2 free 4.70. subtotal 58.84, items 12.20, after 46.64:
                // O5 2.33, OF3 3.00, FD 2.00 -> OF3. total 46.64 - 3.00 + 2.00 = 45.64
                cart("23 everything", "1x3 2x3 3x6 4x4", "P10 P25C F050 B2G1 B1G1 O5 OF3 FD", "2.00",
                        "58.84", "12.20", "3.00", "2.00", "45.64", "NONE"),
                // same cart with the 15% coupon: 46.64 * 15% = 6.996 -> capped 5.00
                cart("24 everything plus coupon", "1x3 2x3 3x6 4x4", "P10 P25C F050 B2G1 B1G1 O5 OF3 FD C15",
                        "2.00", "58.84", "12.20", "5.00", "2.00", "43.64", "APPLIED"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("carts")
    void price_handCalculatedCarts_expectedTotals(String name, List<PricingInput.Line> lines, List<Offer> offers,
                                                  String fee, String subtotal, String productDiscount,
                                                  String orderDiscount, String deliveryFee, String total,
                                                  CouponStatus couponStatus) {
        PricingResult result = calculator.price(new PricingInput(lines, amount(fee), offers));

        assertThat(result.subtotal()).as("subtotal").isEqualTo(amount(subtotal));
        assertThat(result.productDiscount()).as("product discount").isEqualTo(amount(productDiscount));
        assertThat(result.orderDiscount()).as("order discount").isEqualTo(amount(orderDiscount));
        assertThat(result.deliveryFee()).as("delivery fee").isEqualTo(amount(deliveryFee));
        assertThat(result.total()).as("total").isEqualTo(amount(total));
        assertThat(result.couponStatus()).as("coupon").isEqualTo(couponStatus);
        // invariants
        assertThat(result.total()).isEqualTo(calculator.total(result.subtotal(),
                result.productDiscount().add(result.orderDiscount()).add(result.rewardDiscount()),
                result.deliveryFee()));
        assertThat(result.productDiscount()).isEqualByComparingTo(result.lines().stream()
                .map(PricingResult.Line::discountAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        assertThat(result.appliedOffers().stream().filter(o -> o.level() == OfferLevel.ORDER)).hasSizeLessThan(2);
    }

    private static Arguments cart(String name, String lines, String offers, String fee, String subtotal,
                                  String productDiscount, String orderDiscount, String deliveryFee, String total,
                                  String couponStatus) {
        List<PricingInput.Line> parsedLines = Arrays.stream(lines.split(" ")).map(spec -> {
            String[] parts = spec.split("x");
            long productId = Long.parseLong(parts[0]);
            return line(productId, Integer.parseInt(parts[1]), PRICES.get(productId));
        }).toList();
        List<Offer> parsedOffers = offers.isBlank() ? List.of()
                : Arrays.stream(offers.split(" ")).map(OFFERS::get).toList();
        return Arguments.of(name, parsedLines, parsedOffers, fee, subtotal, productDiscount, orderDiscount,
                deliveryFee, total, CouponStatus.valueOf(couponStatus));
    }

    // ---------------------------------------------------------------- helpers

    private PricingResult price(String fee, List<PricingInput.Line> lines, Offer... offers) {
        return calculator.price(new PricingInput(lines, new BigDecimal(fee), List.of(offers)));
    }

    private static void assertLine(PricingResult result, int index, String discount, int free, Long offerId) {
        PricingResult.Line line = result.lines().get(index);
        assertThat(line.discountAmount()).as("discount of line " + index).isEqualTo(amount(discount));
        assertThat(line.freeQuantity()).as("free quantity of line " + index).isEqualTo(free);
        assertThat(line.appliedOfferId()).as("offer of line " + index).isEqualTo(offerId);
    }

    private static PricingInput.Line line(Long productId, int quantity, String unitPrice) {
        return new PricingInput.Line(productId, quantity, new BigDecimal(unitPrice));
    }

    private static BigDecimal amount(String value) {
        return PricingCalculator.money(new BigDecimal(value));
    }

    private static AppliedOffer applied(Long id, OfferLevel level, String discount) {
        return new AppliedOffer(id, level, amount(discount));
    }

    private static Offer itemPercentage(Long id, String percent, String max, Long... products) {
        return new Offer(id, "item " + id, OfferType.PERCENTAGE, OfferLevel.ITEM, new BigDecimal(percent),
                max == null ? null : new BigDecimal(max), null, null, null, Set.of(products), false, IN_30_DAYS);
    }

    private static Offer itemFixed(Long id, String perUnit, Long... products) {
        return new Offer(id, "item " + id, OfferType.FIXED_AMOUNT, OfferLevel.ITEM, new BigDecimal(perUnit), null,
                null, null, null, Set.of(products), false, IN_30_DAYS);
    }

    private static Offer buyXGetY(Long id, int buy, int get, Long... products) {
        return new Offer(id, "bxgy " + id, OfferType.BUY_X_GET_Y, OfferLevel.ITEM, null, null, buy, get, null,
                Set.of(products), false, IN_30_DAYS);
    }

    private static Offer orderPercentage(Long id, String percent, String max, String min) {
        return new Offer(id, "order " + id, OfferType.PERCENTAGE, OfferLevel.ORDER, new BigDecimal(percent),
                max == null ? null : new BigDecimal(max), null, null, min == null ? null : new BigDecimal(min),
                Set.of(), false, IN_30_DAYS);
    }

    private static Offer orderFixed(Long id, String amount, String min) {
        return new Offer(id, "order " + id, OfferType.FIXED_AMOUNT, OfferLevel.ORDER, new BigDecimal(amount), null,
                null, null, min == null ? null : new BigDecimal(min), Set.of(), false, IN_30_DAYS);
    }

    private static Offer freeDelivery(Long id, String min) {
        return new Offer(id, "free delivery " + id, OfferType.FREE_DELIVERY, OfferLevel.ORDER, null, null, null,
                null, min == null ? null : new BigDecimal(min), Set.of(), false, IN_30_DAYS);
    }

    private static Offer coupon(Offer o) {
        return new Offer(o.id(), o.name(), o.type(), o.level(), o.value(), o.maxDiscount(), o.buyQuantity(),
                o.getQuantity(), o.minOrderAmount(), o.productIds(), true, o.endsAt());
    }

    private static Offer withEnd(Offer o, Instant endsAt) {
        return new Offer(o.id(), o.name(), o.type(), o.level(), o.value(), o.maxDiscount(), o.buyQuantity(),
                o.getQuantity(), o.minOrderAmount(), o.productIds(), o.coupon(), endsAt);
    }

    private static Offer withMinimum(Offer o, String min) {
        return new Offer(o.id(), o.name(), o.type(), o.level(), o.value(), o.maxDiscount(), o.buyQuantity(),
                o.getQuantity(), new BigDecimal(min), o.productIds(), o.coupon(), o.endsAt());
    }
}
