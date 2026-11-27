package com.psar.order.service.quote;

import com.psar.catalog.dto.response.SellableProduct;
import com.psar.order.entity.PaymentMethod;
import com.psar.order.service.delivery.DeliveryQuote;
import com.psar.order.service.pricing.PricingResult;
import com.psar.user.dto.response.AddressSnapshot;

import java.util.Map;

/**
 * A cart priced for an address and payment method: everything checkout needs to build the order
 * (address snapshot, delivery company, current products, totals). {@code products} is keyed by product id.
 */
public record PricedCart(
        AddressSnapshot address,
        PaymentMethod paymentMethod,
        DeliveryQuote delivery,
        Map<Long, SellableProduct> products,
        PricingResult pricing
) {
    public PricedCart {
        products = Map.copyOf(products);
    }
}
