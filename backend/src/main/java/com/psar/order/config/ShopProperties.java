package com.psar.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

/** {@code app.shop.*}: where CITY deliveries start (default: Phnom Penh centre). */
@ConfigurationProperties(prefix = "app.shop")
public record ShopProperties(
        @DefaultValue("11.5564") BigDecimal latitude,
        @DefaultValue("104.9282") BigDecimal longitude
) {
}
