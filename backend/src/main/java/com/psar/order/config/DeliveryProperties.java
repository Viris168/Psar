package com.psar.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code app.delivery.*}: CITY delivery fee bands by distance from the shop (see DeliveryFeeCalculator).
 *
 * @param cityBands in increasing {@code max-km} order; the last band has no {@code max-km}
 */
@ConfigurationProperties(prefix = "app.delivery")
public record DeliveryProperties(List<Band> cityBands) {

    /**
     * @param maxKm upper edge in km (exclusive); empty for the last band
     * @param fee   the fee in USD
     */
    public record Band(BigDecimal maxKm, BigDecimal fee) {
    }

    public DeliveryProperties {
        cityBands = cityBands == null || cityBands.isEmpty()
                ? List.of(new Band(new BigDecimal("5"), new BigDecimal("1.00")),
                          new Band(new BigDecimal("15"), new BigDecimal("2.00")),
                          new Band(null, new BigDecimal("3.00")))
                : List.copyOf(cityBands);
    }
}
