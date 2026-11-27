package com.psar.order.service.delivery;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Pure unit tests with the default bands: < 5 km 1.00, 5 to < 15 km 2.00, >= 15 km 3.00. */
class DeliveryFeeCalculatorTest {

    private static final BigDecimal SHOP_LAT = new BigDecimal("11.5564");
    private static final BigDecimal SHOP_LNG = new BigDecimal("104.9282");

    private final DeliveryFeeCalculator calculator = new DeliveryFeeCalculator(SHOP_LAT, SHOP_LNG, List.of(
            band("5", "1.00"), band("15", "2.00"), band(null, "3.00")));

    @Test
    void feeForDistance_firstBand_oneDollar() {
        assertThat(calculator.feeForDistance(0)).isEqualTo(new BigDecimal("1.00"));
        assertThat(calculator.feeForDistance(2.5)).isEqualTo(new BigDecimal("1.00"));
    }

    @Test
    void feeForDistance_justBelowFiveKm_firstBand() {
        assertThat(calculator.feeForDistance(4.99)).isEqualTo(new BigDecimal("1.00"));
    }

    @Test
    void feeForDistance_exactlyFiveKm_secondBand() {
        assertThat(calculator.feeForDistance(5.00)).isEqualTo(new BigDecimal("2.00"));
    }

    @Test
    void feeForDistance_justBelowFifteenKm_secondBand() {
        assertThat(calculator.feeForDistance(14.99)).isEqualTo(new BigDecimal("2.00"));
    }

    @Test
    void feeForDistance_exactlyFifteenKm_lastBand() {
        assertThat(calculator.feeForDistance(15.00)).isEqualTo(new BigDecimal("3.00"));
    }

    @Test
    void feeForDistance_farAway_lastBand() {
        assertThat(calculator.feeForDistance(40)).isEqualTo(new BigDecimal("3.00"));
    }

    @Test
    void fee_theShopItself_firstBand() {
        assertThat(calculator.distanceKm(SHOP_LAT, SHOP_LNG)).isZero();
        assertThat(calculator.fee(SHOP_LAT, SHOP_LNG)).isEqualTo(new BigDecimal("1.00"));
    }

    @Test
    void distanceKm_tenthOfADegreeNorth_about11Km() {
        // one degree of latitude is ~111.2 km everywhere
        double km = calculator.distanceKm(SHOP_LAT.add(new BigDecimal("0.1")), SHOP_LNG);

        assertThat(km).isCloseTo(11.12, within(0.01));
        assertThat(calculator.fee(SHOP_LAT.add(new BigDecimal("0.1")), SHOP_LNG)).isEqualTo(new BigDecimal("2.00"));
    }

    @Test
    void distanceKm_knownCityPair_matchesHaversine() {
        // Phnom Penh centre -> Siem Reap (13.3633, 103.8564): ~200.9 km north, ~116.3 km west -> ~232.2 km
        double km = calculator.distanceKm(new BigDecimal("13.3633"), new BigDecimal("103.8564"));

        assertThat(km).isCloseTo(232.2, within(0.5));
    }

    @Test
    void fee_pinsAcrossTheCity_bandsGrowWithDistance() {
        BigDecimal near = calculator.fee(new BigDecimal("11.5700"), new BigDecimal("104.9200"));   // ~1.8 km
        BigDecimal middle = calculator.fee(new BigDecimal("11.6400"), new BigDecimal("104.9282")); // ~9.3 km
        BigDecimal far = calculator.fee(new BigDecimal("11.7400"), new BigDecimal("104.9282"));    // ~20.4 km

        assertThat(List.of(near, middle, far)).containsExactly(
                new BigDecimal("1.00"), new BigDecimal("2.00"), new BigDecimal("3.00"));
    }

    @Test
    void constructor_lastBandHasMaxKm_throws() {
        assertThatThrownBy(() -> new DeliveryFeeCalculator(SHOP_LAT, SHOP_LNG, List.of(band("5", "1.00"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_bandsOutOfOrder_throws() {
        assertThatThrownBy(() -> new DeliveryFeeCalculator(SHOP_LAT, SHOP_LNG, List.of(
                band("15", "2.00"), band("5", "1.00"), band(null, "3.00"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_noBands_throws() {
        assertThatThrownBy(() -> new DeliveryFeeCalculator(SHOP_LAT, SHOP_LNG, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DeliveryFeeCalculator.Band band(String maxKm, String fee) {
        return new DeliveryFeeCalculator.Band(maxKm == null ? null : new BigDecimal(maxKm), new BigDecimal(fee));
    }
}
