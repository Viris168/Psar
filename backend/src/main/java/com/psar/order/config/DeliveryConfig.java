package com.psar.order.config;

import com.psar.order.service.delivery.DeliveryFeeCalculator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** DeliveryFeeCalculator is pure (no Spring), so it is built here from {@code app.shop.*} and {@code app.delivery.*}. */
@Configuration
@EnableConfigurationProperties({ShopProperties.class, DeliveryProperties.class})
public class DeliveryConfig {

    @Bean
    public DeliveryFeeCalculator deliveryFeeCalculator(ShopProperties shop, DeliveryProperties delivery) {
        return new DeliveryFeeCalculator(shop.latitude(), shop.longitude(), delivery.cityBands().stream()
                .map(band -> new DeliveryFeeCalculator.Band(band.maxKm(), band.fee()))
                .toList());
    }
}
