package com.psar.order.service.delivery;

import com.psar.common.exception.BusinessRuleException;
import com.psar.delivery.dto.response.DeliveryCompanySnapshot;
import com.psar.delivery.service.DeliveryCompanyService;
import com.psar.order.entity.PaymentMethod;
import com.psar.order.service.pricing.PricingCalculator;
import com.psar.user.dto.response.AddressSnapshot;
import com.psar.user.service.AddressLookupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;

/**
 * Delivery fee and rules for an address (PRD §3.2). Throws 422 (BusinessRuleException) with a clear message when
 * the address can't be delivered with this payment method:
 * <ul>
 *   <li><b>CITY</b>: the pin must be present and inside Phnom Penh (the box the address form uses); KHQR or COD;
 *       fee by distance from the shop (DeliveryFeeCalculator).</li>
 *   <li><b>PROVINCE</b>: KHQR only; a delivery company that is still active and a courier branch;
 *       fee = the company's base fee.</li>
 * </ul>
 * Checkout and the checkout quote both call this, so the fee a customer is shown is the fee they pay.
 */
@Service
@RequiredArgsConstructor
public class DeliveryQuoteService {

    private final DeliveryCompanyService deliveryCompanyService;
    private final DeliveryFeeCalculator deliveryFeeCalculator;

    @Transactional(readOnly = true)
    public DeliveryQuote quote(AddressSnapshot address, PaymentMethod method) {
        Objects.requireNonNull(address, "address");
        if (method == null) {
            throw new BusinessRuleException("Choose a payment method",
                    Map.of("paymentMethod", "must be KHQR or CASH_ON_DELIVERY"));
        }
        return switch (address.deliveryType()) {
            case "CITY" -> cityQuote(address);
            case "PROVINCE" -> provinceQuote(address, method);
            default -> throw new IllegalStateException("Unknown delivery type: " + address.deliveryType());
        };
    }

    private DeliveryQuote cityQuote(AddressSnapshot address) {
        if (address.latitude() == null || address.longitude() == null) {
            throw new BusinessRuleException("This address has no map pin; edit the address and set the pin",
                    Map.of("address", "map pin is missing"));
        }
        if (!AddressLookupService.insidePhnomPenh(address.latitude(), address.longitude())) {
            throw new BusinessRuleException(
                    "This address is outside Phnom Penh; use a province address with a delivery company",
                    Map.of("address", "map pin is outside Phnom Penh"));
        }
        return new DeliveryQuote(deliveryFeeCalculator.fee(address.latitude(), address.longitude()), null);
    }

    private DeliveryQuote provinceQuote(AddressSnapshot address, PaymentMethod method) {
        if (method != PaymentMethod.KHQR) {
            throw new BusinessRuleException("Cash on delivery is only available in Phnom Penh; pay with KHQR",
                    Map.of("paymentMethod", "province orders must be paid with KHQR"));
        }
        if (address.deliveryCompanyId() == null) {
            throw new BusinessRuleException("Choose a delivery company for this address",
                    Map.of("address", "delivery company is missing"));
        }
        DeliveryCompanySnapshot company = deliveryCompanyService.snapshot(address.deliveryCompanyId());
        if (!company.active()) {
            throw new BusinessRuleException(
                    company.name() + " no longer delivers; edit the address and choose another company",
                    Map.of("address", "delivery company is inactive"));
        }
        if (address.courierBranch() == null || address.courierBranch().isBlank()) {
            throw new BusinessRuleException("Enter the courier branch for this address",
                    Map.of("address", "courier branch is missing"));
        }
        return new DeliveryQuote(PricingCalculator.money(company.baseFee()), company);
    }
}
