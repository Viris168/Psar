package com.psar.order.service.quote;

import com.psar.catalog.dto.response.SellableProduct;
import com.psar.catalog.service.ProductCatalogService;
import com.psar.common.exception.BusinessRuleException;
import com.psar.order.dto.response.CheckoutQuoteResponse;
import com.psar.order.entity.Cart;
import com.psar.order.entity.CartItem;
import com.psar.order.entity.DeliveryType;
import com.psar.order.entity.PaymentMethod;
import com.psar.order.repository.CartRepository;
import com.psar.order.service.delivery.DeliveryQuote;
import com.psar.order.service.delivery.DeliveryQuoteService;
import com.psar.order.service.pricing.PricingCalculator;
import com.psar.order.service.pricing.PricingInput;
import com.psar.order.service.pricing.PricingResult;
import com.psar.user.dto.response.AddressSnapshot;
import com.psar.user.service.AddressLookupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the current cart would cost, without placing an order. It runs the same steps as checkout
 * (architecture.md §5.1, steps 1–4): purchasable products with current DB prices, DeliveryQuoteService for the
 * delivery rules and fee, PricingCalculator for the totals. Checkout can call {@link #price} with its locked
 * cart's lines so the numbers always match.
 */
@Service
@RequiredArgsConstructor
public class CheckoutQuoteService {

    private final CartRepository cartRepository;
    private final ProductCatalogService productCatalogService;
    private final AddressLookupService addressLookupService;
    private final DeliveryQuoteService deliveryQuoteService;
    private final PricingCalculator pricingCalculator;

    /** The quote for my cart: 404 for someone else's address, 422 for an empty cart or a delivery rule. */
    @Transactional(readOnly = true)
    public CheckoutQuoteResponse quote(Long userId, UUID addressPublicId, PaymentMethod method) {
        Map<Long, Integer> quantities = quantities(
                cartRepository.findByUserId(userId).map(Cart::getItems).orElse(List.of()));
        AddressSnapshot address = addressLookupService.requireOwned(userId, addressPublicId);
        return toResponse(price(quantities, address, method));
    }

    /**
     * Prices cart lines (product id → quantity, in cart order) for an address and payment method.
     * Empty → 422; a product that can't be bought any more → 422 naming it. Prices come from the database only.
     */
    @Transactional(readOnly = true)
    public PricedCart price(Map<Long, Integer> quantities, AddressSnapshot address, PaymentMethod method) {
        if (quantities.isEmpty()) {
            throw new BusinessRuleException("Your cart is empty");
        }
        Map<Long, SellableProduct> products = productCatalogService.sellable(quantities.keySet());
        List<PricingInput.Line> lines = quantities.entrySet().stream().map(entry -> {
            SellableProduct product = products.get(entry.getKey());
            if (product == null || !product.purchasable()) {
                throw new BusinessRuleException((product == null ? "A product in your cart" : product.name())
                        + " is no longer available; remove it from your cart");
            }
            return new PricingInput.Line(product.id(), entry.getValue(), product.sellPrice());
        }).toList();

        DeliveryQuote delivery = deliveryQuoteService.quote(address, method);
        PricingResult pricing = pricingCalculator.price(new PricingInput(lines, delivery.fee()));
        return new PricedCart(address, method, delivery, products, pricing);
    }

    private static CheckoutQuoteResponse toResponse(PricedCart cart) {
        PricingResult pricing = cart.pricing();
        List<CheckoutQuoteResponse.Line> lines = pricing.lines().stream().map(line -> {
            SellableProduct product = cart.products().get(line.productId());
            return new CheckoutQuoteResponse.Line(product.publicId(), product.slug(), product.name(),
                    product.unit(), product.mainImageUrl(), line.quantity(), line.unitPrice(),
                    line.discountAmount(), line.lineTotal());
        }).toList();
        return new CheckoutQuoteResponse(lines, pricing.subtotal(), pricing.productDiscount(),
                pricing.orderDiscount(), pricing.rewardDiscount(), pricing.deliveryFee(), pricing.total(),
                DeliveryType.valueOf(cart.address().deliveryType()),
                cart.delivery().company() == null ? null : cart.delivery().company().name(),
                cart.paymentMethod());
    }

    /** Cart item quantities in cart order (for callers that already hold the cart, e.g. checkout). */
    public static Map<Long, Integer> quantities(List<CartItem> items) {
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        items.forEach(item -> quantities.merge(item.getProductId(), item.getQuantity(), Integer::sum));
        return quantities;
    }
}
