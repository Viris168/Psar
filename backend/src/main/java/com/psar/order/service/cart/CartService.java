package com.psar.order.service.cart;

import com.psar.catalog.dto.response.SellableProduct;
import com.psar.catalog.service.ProductCatalogService;
import com.psar.common.exception.BusinessRuleException;
import com.psar.common.exception.NotFoundException;
import com.psar.order.dto.request.AddCartItemRequest;
import com.psar.order.dto.request.UpdateCartItemRequest;
import com.psar.order.dto.response.CartItemResponse;
import com.psar.order.dto.response.CartResponse;
import com.psar.order.entity.Cart;
import com.psar.order.entity.CartItem;
import com.psar.order.repository.CartRepository;
import com.psar.order.service.pricing.PricingCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The customer's cart (one per customer, created on first add). Lines hold only product + quantity; the response
 * always shows current prices from the catalog, never stock numbers.
 */
@Service
@RequiredArgsConstructor
public class CartService {

    public static final int MAX_LINES = 50;

    private final CartRepository cartRepository;
    private final ProductCatalogService productCatalogService;

    /** My cart; an empty one if I never added anything (nothing is written). */
    @Transactional(readOnly = true)
    public CartResponse get(Long userId) {
        return cartRepository.findByUserId(userId)
                .map(this::toResponse)
                .orElseGet(() -> new CartResponse(List.of(), 0, PricingCalculator.money(BigDecimal.ZERO)));
    }

    /**
     * Adds to the product's line (adding again adds up). Only purchasable products (422); a line holds at most
     * {@value CartItem#MAX_QUANTITY} units and the cart at most {@value #MAX_LINES} lines (422).
     */
    @Transactional
    public CartResponse add(Long userId, AddCartItemRequest request) {
        SellableProduct product = productCatalogService.sellable(request.productPublicId());
        if (!product.purchasable()) {
            throw new BusinessRuleException(product.name() + " is not available");
        }
        Cart cart = cartRepository.findByUserId(userId).orElseGet(() -> newCart(userId));
        cart.item(product.id()).ifPresentOrElse(item -> {
            int quantity = item.getQuantity() + request.quantity();
            if (quantity > CartItem.MAX_QUANTITY) {
                throw new BusinessRuleException("At most " + CartItem.MAX_QUANTITY + " of one product per order",
                        Map.of("quantity", "at most " + CartItem.MAX_QUANTITY + " in total"));
            }
            item.setQuantity(quantity);
        }, () -> {
            if (cart.getItems().size() >= MAX_LINES) {
                throw new BusinessRuleException("A cart holds at most " + MAX_LINES + " different products");
            }
            cart.addItem(product.id(), request.quantity());
        });
        return toResponse(cartRepository.save(cart));
    }

    /** Sets a line's quantity; a product that isn't in my cart is a 404. */
    @Transactional
    public CartResponse setQuantity(Long userId, UUID productPublicId, UpdateCartItemRequest request) {
        Long productId = productCatalogService.sellable(productPublicId).id();
        Cart cart = cartRepository.findByUserId(userId).orElseThrow(() -> NotFoundException.of("Cart item"));
        CartItem item = cart.item(productId).orElseThrow(() -> NotFoundException.of("Cart item"));
        item.setQuantity(request.quantity());
        return toResponse(cartRepository.save(cart));
    }

    /** Removes a line; removing a product that isn't in the cart changes nothing. */
    @Transactional
    public CartResponse remove(Long userId, UUID productPublicId) {
        Long productId = productCatalogService.sellable(productPublicId).id();
        return cartRepository.findByUserId(userId)
                .map(cart -> {
                    cart.removeItem(productId);
                    return toResponse(cartRepository.save(cart));
                })
                .orElseGet(() -> get(userId));
    }

    @Transactional
    public void clear(Long userId) {
        cartRepository.findByUserId(userId).ifPresent(cart -> {
            cart.clear();
            cartRepository.save(cart);
        });
    }

    private static Cart newCart(Long userId) {
        Cart cart = new Cart();
        cart.setUserId(userId);
        return cart;
    }

    private CartResponse toResponse(Cart cart) {
        Map<Long, SellableProduct> products = productCatalogService.sellable(
                cart.getItems().stream().map(CartItem::getProductId).toList());
        BigDecimal subtotal = BigDecimal.ZERO;
        int totalQuantity = 0;
        List<CartItemResponse> lines = new ArrayList<>();
        for (CartItem item : cart.getItems()) {
            SellableProduct product = products.get(item.getProductId());
            if (product == null) {
                continue; // products are never hard-deleted; skip defensively
            }
            BigDecimal unitPrice = PricingCalculator.money(product.sellPrice());
            BigDecimal lineTotal = PricingCalculator.money(unitPrice.multiply(BigDecimal.valueOf(item.getQuantity())));
            if (product.purchasable()) {
                subtotal = subtotal.add(lineTotal);
            }
            totalQuantity += item.getQuantity();
            lines.add(new CartItemResponse(product.publicId(), product.slug(), product.name(), product.unit(),
                    product.mainImageUrl(), unitPrice, item.getQuantity(), lineTotal, product.purchasable(),
                    product.inStock()));
        }
        return new CartResponse(lines, totalQuantity, PricingCalculator.money(subtotal));
    }
}
