package com.psar.order.service.quote;

import com.psar.catalog.dto.response.SellableProduct;
import com.psar.order.entity.PaymentMethod;
import com.psar.order.service.delivery.DeliveryQuote;
import com.psar.order.service.pricing.PricingResult;
import com.psar.promotion.dto.response.OfferSet;
import com.psar.user.dto.response.AddressSnapshot;

import java.util.Map;

/**
 * A cart priced for an address and payment method: everything checkout needs to build the order
 * (address snapshot, delivery company, current products, totals). {@code products} is keyed by product id.
 * {@code offers} are the promotions that could apply (names for the response, the coupon problem if any);
 * {@code pricing.appliedOffers()} are the ones that were used.
 */
public record PricedCart(
        AddressSnapshot address,
        PaymentMethod paymentMethod,
        DeliveryQuote delivery,
        Map<Long, SellableProduct> products,
        OfferSet offers,
        PricingResult pricing
) {
    public PricedCart {
        products = Map.copyOf(products);
    }
}
