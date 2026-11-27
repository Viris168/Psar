package com.psar.order.service.delivery;

import com.psar.delivery.dto.response.DeliveryCompanySnapshot;
import com.psar.delivery.service.DeliveryCompanyService;
import com.psar.order.entity.PaymentMethod;
import com.psar.order.service.pricing.PricingCalculator;
import com.psar.user.dto.response.AddressSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Delivery fee and rules for an address (PRD §3.2). Throws 422 (BusinessRuleException) with a clear message when
 * the address can't be delivered with this payment method.
 * <p>
 * Step 0 version: CITY flat 1.00, PROVINCE the company's base fee, no rule checks. Agent C (Phase 4) adds the
 * distance bands and the checks; the signature stays.
 */
@Service
@RequiredArgsConstructor
public class DeliveryQuoteService {

    private static final BigDecimal STEP0_CITY_FEE = new BigDecimal("1.00");

    private final DeliveryCompanyService deliveryCompanyService;

    @Transactional(readOnly = true)
    public DeliveryQuote quote(AddressSnapshot address, PaymentMethod method) {
        if ("PROVINCE".equals(address.deliveryType())) {
            DeliveryCompanySnapshot company = deliveryCompanyService.snapshot(address.deliveryCompanyId());
            return new DeliveryQuote(PricingCalculator.money(company.baseFee()), company);
        }
        return new DeliveryQuote(STEP0_CITY_FEE, null);
    }
}
