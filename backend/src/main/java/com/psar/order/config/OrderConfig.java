package com.psar.order.config;

import com.psar.order.service.pricing.PricingCalculator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** PricingCalculator is pure (no Spring, ArchUnit), so it is registered here instead of with @Component. */
@Configuration
public class OrderConfig {

    @Bean
    public PricingCalculator pricingCalculator() {
        return new PricingCalculator();
    }
}
