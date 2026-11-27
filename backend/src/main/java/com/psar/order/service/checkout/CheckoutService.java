package com.psar.order.service.checkout;

import com.psar.catalog.dto.response.SellableProduct;
import com.psar.catalog.service.ProductCatalogService;
import com.psar.common.exception.BusinessRuleException;
import com.psar.common.exception.ConflictException;
import com.psar.common.exception.ErrorCode;
import com.psar.inventory.service.StockService;
import com.psar.order.dto.request.CheckoutRequest;
import com.psar.order.dto.response.CheckoutResponse;
import com.psar.order.entity.Cart;
import com.psar.order.entity.CartItem;
import com.psar.order.entity.DeliveryType;
import com.psar.order.entity.Order;
import com.psar.order.entity.OrderItem;
import com.psar.order.entity.OrderStatus;
import com.psar.order.entity.OrderStatusHistory;
import com.psar.order.entity.Payment;
import com.psar.order.entity.PaymentMethod;
import com.psar.order.event.OrderPlacedEvent;
import com.psar.order.repository.CartRepository;
import com.psar.order.repository.OrderRepository;
import com.psar.order.repository.OrderStatusHistoryRepository;
import com.psar.order.repository.PaymentRepository;
import com.psar.order.service.delivery.DeliveryQuote;
import com.psar.order.service.delivery.DeliveryQuoteService;
import com.psar.order.service.pricing.PricingCalculator;
import com.psar.order.service.pricing.PricingInput;
import com.psar.order.service.pricing.PricingResult;
import com.psar.user.dto.response.AddressSnapshot;
import com.psar.user.service.AddressLookupService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns the customer's cart into an order in <b>one transaction</b> (architecture.md §5.1). Any failure (empty
 * cart, unavailable product, delivery rule, not enough stock) rolls everything back: no order, no stock movement,
 * and the cart is kept. Prices always come from the database; the client sends none.
 */
@Service
public class CheckoutService {

    private final CartRepository cartRepository;
    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final PaymentRepository paymentRepository;
    private final ProductCatalogService productCatalogService;
    private final AddressLookupService addressLookupService;
    private final DeliveryQuoteService deliveryQuoteService;
    private final PricingCalculator pricingCalculator;
    private final StockService stockService;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final Duration paymentTimeout;

    @SuppressWarnings("java:S107") // one collaborator per checkout step
    public CheckoutService(CartRepository cartRepository, OrderRepository orderRepository,
                           OrderStatusHistoryRepository historyRepository, PaymentRepository paymentRepository,
                           ProductCatalogService productCatalogService, AddressLookupService addressLookupService,
                           DeliveryQuoteService deliveryQuoteService, PricingCalculator pricingCalculator,
                           StockService stockService, ApplicationEventPublisher events, Clock clock,
                           @Value("${app.order.payment-timeout:PT15M}") Duration paymentTimeout) {
        this.cartRepository = cartRepository;
        this.orderRepository = orderRepository;
        this.historyRepository = historyRepository;
        this.paymentRepository = paymentRepository;
        this.productCatalogService = productCatalogService;
        this.addressLookupService = addressLookupService;
        this.deliveryQuoteService = deliveryQuoteService;
        this.pricingCalculator = pricingCalculator;
        this.stockService = stockService;
        this.events = events;
        this.clock = clock;
        this.paymentTimeout = paymentTimeout;
    }

    @Transactional
    public CheckoutResponse checkout(Long userId, CheckoutRequest request) {
        // 1. the cart, locked: a double click waits here and then finds the cart empty
        Cart cart = cartRepository.lockByUserId(userId)
                .filter(c -> !c.getItems().isEmpty())
                .orElseThrow(() -> new BusinessRuleException("Your cart is empty"));
        List<CartItem> cartItems = cart.getItems().stream()
                .sorted(Comparator.comparing(CartItem::getProductId))
                .toList();
        AddressSnapshot address = addressLookupService.requireOwned(userId, request.addressPublicId());

        // 2. current products and prices from the database
        Map<Long, SellableProduct> products = productCatalogService.sellable(
                cartItems.stream().map(CartItem::getProductId).toList());
        for (CartItem item : cartItems) {
            SellableProduct product = products.get(item.getProductId());
            if (product == null || !product.purchasable()) {
                String name = product == null ? "A product in your cart" : product.name();
                throw new BusinessRuleException(name + " is no longer available; remove it from your cart");
            }
        }

        // 3. delivery rules and fee (422 on violations)
        DeliveryQuote quote = deliveryQuoteService.quote(address, request.paymentMethod());

        // 4. price
        PricingResult price = pricingCalculator.price(new PricingInput(
                cartItems.stream()
                        .map(item -> new PricingInput.Line(item.getProductId(), item.getQuantity(),
                                products.get(item.getProductId()).sellPrice()))
                        .toList(),
                quote.fee()));

        // 5. the order with its snapshots, items and first history row
        Instant now = clock.instant();
        boolean khqr = request.paymentMethod() == PaymentMethod.KHQR;
        Order order = newOrder(userId, request, address, quote, price, now, khqr);
        for (PricingResult.Line line : price.lines()) {
            order.addItem(newItem(line, products.get(line.productId())));
        }
        order = orderRepository.saveAndFlush(order);
        historyRepository.save(OrderStatusHistory.of(order.getId(), order.getStatus(), userId, null, now));

        // 6. reserve stock (409 INSUFFICIENT_STOCK rolls the whole checkout back)
        reserve(order, products, userId);

        // 7. the payment, PENDING (KHQR code comes in Phase 5)
        Payment payment = new Payment();
        payment.setOrderId(order.getId());
        payment.setMethod(request.paymentMethod());
        payment.setAmount(order.getTotalAmount());
        payment.setExpiresAt(order.getExpiresAt());
        paymentRepository.save(payment);

        // 8. clear the cart, tell listeners (they run after commit)
        cart.clear();
        events.publishEvent(new OrderPlacedEvent(order.getId()));

        // 9.
        return toResponse(order, products);
    }

    private Order newOrder(Long userId, CheckoutRequest request, AddressSnapshot address, DeliveryQuote quote,
                           PricingResult price, Instant now, boolean khqr) {
        Order order = new Order();
        order.setUserId(userId);
        order.setPaymentMethod(request.paymentMethod());
        order.setStatus(khqr ? OrderStatus.PENDING_PAYMENT : OrderStatus.CONFIRMED);
        order.setExpiresAt(khqr ? now.plus(paymentTimeout) : null);

        order.setDeliveryType(DeliveryType.valueOf(address.deliveryType()));
        order.setReceiverName(address.receiverName());
        order.setReceiverPhone(address.phone());
        order.setProvince(address.province());
        order.setShippingAddress(address.singleLine());
        order.setShippingLatitude(address.latitude());
        order.setShippingLongitude(address.longitude());
        order.setShippingLandmark(address.landmark());
        if (quote.company() != null) {
            order.setDeliveryCompanyId(quote.company().id());
            order.setDeliveryCompanyName(quote.company().name());
            order.setCourierBranch(address.courierBranch());
        }

        order.setSubtotal(price.subtotal());
        order.setProductDiscount(price.productDiscount());
        order.setOrderDiscount(price.orderDiscount());
        order.setRewardDiscount(price.rewardDiscount());
        order.setDeliveryFee(price.deliveryFee());
        order.setTotalAmount(price.total());
        order.setNote(StringUtils.hasText(request.note()) ? request.note().trim() : null);
        return order;
    }

    private static OrderItem newItem(PricingResult.Line line, SellableProduct product) {
        OrderItem item = new OrderItem();
        item.setProductId(line.productId());
        item.setProductName(product.name());
        item.setSku(product.sku());
        item.setQuantity(line.quantity());
        item.setFreeQuantity(line.freeQuantity());
        item.setUnitPrice(line.unitPrice());
        item.setDiscountAmount(line.discountAmount());
        item.setLineTotal(line.lineTotal());
        return item;
    }

    /**
     * One {@link StockService#reserve} call per product, in product id order (so concurrent checkouts can't
     * deadlock), so a shortage can name the product. The failure has already marked the transaction for rollback.
     */
    private void reserve(Order order, Map<Long, SellableProduct> products, Long userId) {
        Map<Long, Integer> quantities = new TreeMap<>();
        order.getItems().forEach(item -> quantities.merge(item.getProductId(), item.stockQuantity(), Integer::sum));
        quantities.forEach((productId, qty) -> {
            try {
                stockService.reserve(Map.of(productId, qty), order.getId(), userId);
            } catch (ConflictException e) {
                if (e.getErrorCode() != ErrorCode.INSUFFICIENT_STOCK) {
                    throw e;
                }
                throw new ConflictException(ErrorCode.INSUFFICIENT_STOCK,
                        "Not enough stock for " + products.get(productId).name());
            }
        });
    }

    private static CheckoutResponse toResponse(Order order, Map<Long, SellableProduct> products) {
        List<CheckoutResponse.Line> lines = order.getItems().stream()
                .map(item -> new CheckoutResponse.Line(products.get(item.getProductId()).publicId(),
                        item.getProductName(), item.getSku(), item.getQuantity(), item.getFreeQuantity(),
                        item.getUnitPrice(), item.getDiscountAmount(), item.getLineTotal()))
                .toList();
        return new CheckoutResponse(order.getPublicId(), order.getOrderNo(), order.getStatus(),
                order.getPaymentMethod(), order.getDeliveryType(), order.getDeliveryCompanyName(), lines,
                order.getSubtotal(), order.getProductDiscount(), order.getOrderDiscount(), order.getRewardDiscount(),
                order.getDeliveryFee(), order.getTotalAmount(), order.getExpiresAt(), order.getCreatedAt());
    }
}
