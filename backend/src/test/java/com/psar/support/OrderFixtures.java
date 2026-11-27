package com.psar.support;

import com.psar.catalog.entity.Product;
import com.psar.delivery.entity.DeliveryCompany;
import com.psar.delivery.repository.DeliveryCompanyRepository;
import com.psar.inventory.service.StockService;
import com.psar.order.entity.Cart;
import com.psar.order.entity.DeliveryType;
import com.psar.order.entity.Order;
import com.psar.order.entity.OrderItem;
import com.psar.order.entity.OrderStatus;
import com.psar.order.entity.OrderStatusHistory;
import com.psar.order.entity.PaymentMethod;
import com.psar.order.repository.CartRepository;
import com.psar.order.repository.OrderRepository;
import com.psar.order.repository.OrderStatusHistoryRepository;
import com.psar.payment.service.PaymentService;
import com.psar.user.entity.Address;
import com.psar.user.entity.Role;
import com.psar.user.entity.User;
import com.psar.user.entity.UserProfile;
import com.psar.user.repository.AddressRepository;
import com.psar.user.repository.UserProfileRepository;
import com.psar.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Customers, addresses, drivers, delivery companies, carts and orders for tests.
 * Available in every IntegrationTest: {@code @Autowired OrderFixtures orders;}
 * <p>
 * {@link #order} builds an order the way checkout would (snapshot, items, history row, PENDING payment) and
 * reserves its stock through StockService, so cancel / expiry tests see real RESERVE movements. The payment is
 * created by PaymentService exactly as checkout does (KHQR: through the gateway).
 */
@TestComponent
@RequiredArgsConstructor
public class OrderFixtures {

    /** Phnom Penh centre: the default shop location, so CITY orders here are in the first fee band. */
    public static final BigDecimal PP_LAT = new BigDecimal("11.556400");
    public static final BigDecimal PP_LNG = new BigDecimal("104.928200");

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final AddressRepository addressRepository;
    private final DeliveryCompanyRepository deliveryCompanyRepository;
    private final CartRepository cartRepository;
    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final PaymentService paymentService;
    private final StockService stockService;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;

    // ---- people and places -------------------------------------------------------------------------

    public User customer() {
        return user(Role.CUSTOMER, "Customer", null);
    }

    /** An active STAFF user with position "Driver". */
    public User driver() {
        return user(Role.STAFF, "Driver", "Driver");
    }

    public Address cityAddress(User customer) {
        Address address = base(customer, "CITY");
        address.setProvince("Phnom Penh");
        address.setLatitude(PP_LAT);
        address.setLongitude(PP_LNG);
        address.setHouseNo("12");
        address.setStreet("St. 13");
        address.setDistrict("Daun Penh");
        address.setLandmark("Near the market");
        return addressRepository.save(address);
    }

    public Address provinceAddress(User customer, DeliveryCompany company) {
        Address address = base(customer, "PROVINCE");
        address.setProvince("Siem Reap");
        address.setDeliveryCompanyId(company.getId());
        address.setCourierBranch("Siem Reap branch");
        return addressRepository.save(address);
    }

    public DeliveryCompany deliveryCompany(String baseFee) {
        DeliveryCompany company = new DeliveryCompany();
        company.setName("Courier " + shortId());
        company.setBaseFee(new BigDecimal(baseFee));
        company.setTrackingUrl("https://track.example/{tracking_no}");
        return deliveryCompanyRepository.save(company);
    }

    // ---- carts and orders --------------------------------------------------------------------------

    /** The customer's cart with these products and quantities (replacing what was there). */
    public Cart cart(User customer, Map<Product, Integer> lines) {
        Cart cart = cartRepository.findByUserId(customer.getId()).orElseGet(() -> {
            Cart created = new Cart();
            created.setUserId(customer.getId());
            return created;
        });
        cart.clear();
        lines.forEach((product, qty) -> cart.addItem(product.getId(), qty));
        return cartRepository.save(cart);
    }

    /**
     * An order as checkout would create it, in {@code status}, with its stock reserved. KHQR orders in
     * PENDING_PAYMENT expire 15 minutes from the (test) clock's now.
     */
    public Order order(User customer, Address address, PaymentMethod method, OrderStatus status,
                       Map<Product, Integer> lines) {
        return new TransactionTemplate(transactionManager).execute(tx -> {
            Instant now = clock.instant();
            Order order = new Order();
            order.setUserId(customer.getId());
            order.setStatus(status);
            order.setPaymentMethod(method);
            order.setExpiresAt(status == OrderStatus.PENDING_PAYMENT ? now.plus(Duration.ofMinutes(15)) : null);
            order.setDeliveryType(DeliveryType.valueOf(address.getDeliveryType().name()));
            order.setReceiverName(address.getReceiverName());
            order.setReceiverPhone(address.getPhone());
            order.setProvince(address.getProvince());
            order.setShippingAddress(address.getStreet() == null ? address.getProvince() : address.getStreet());
            order.setShippingLatitude(address.getLatitude());
            order.setShippingLongitude(address.getLongitude());
            order.setDeliveryCompanyId(address.getDeliveryCompanyId());
            order.setCourierBranch(address.getCourierBranch());

            BigDecimal subtotal = BigDecimal.ZERO;
            for (Map.Entry<Product, Integer> line : lines.entrySet()) {
                Product product = line.getKey();
                OrderItem item = new OrderItem();
                item.setProductId(product.getId());
                item.setProductName(product.getName());
                item.setSku(product.getSku());
                item.setQuantity(line.getValue());
                item.setUnitPrice(product.getSellPrice());
                item.setLineTotal(money(product.getSellPrice().multiply(BigDecimal.valueOf(line.getValue()))));
                order.addItem(item);
                subtotal = subtotal.add(item.getLineTotal());
            }
            BigDecimal fee = new BigDecimal("1.00");
            order.setSubtotal(money(subtotal));
            order.setDeliveryFee(fee);
            order.setTotalAmount(money(subtotal.add(fee)));
            order = orderRepository.saveAndFlush(order);

            historyRepository.save(OrderStatusHistory.of(order.getId(), status, null, null, now));
            // a KHQR payment always had a 15-minute QR at checkout, even if the order has moved on since
            Instant paymentExpiresAt = method == PaymentMethod.KHQR && order.getExpiresAt() == null
                    ? now.plus(Duration.ofMinutes(15)) : order.getExpiresAt();
            paymentService.createForOrder(order.getId(), order.getOrderNo(), method.name(), order.getTotalAmount(),
                    paymentExpiresAt);

            Map<Long, Integer> quantities = lines.entrySet().stream()
                    .collect(Collectors.toMap(e -> e.getKey().getId(), Map.Entry::getValue, Integer::sum,
                            LinkedHashMap::new));
            stockService.reserve(quantities, order.getId(), customer.getId());
            return orderRepository.findById(order.getId()).orElseThrow();
        });
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private User user(Role role, String fullName, String position) {
        User user = new User();
        user.setEmail(role.name().toLowerCase() + "-" + shortId() + "@test.local");
        user.setPasswordHash("not-used");
        user.setRole(role);
        user = userRepository.save(user);
        UserProfile profile = new UserProfile();
        profile.setUser(user);
        profile.setFullName(fullName + " " + shortId());
        profile.setPosition(position);
        userProfileRepository.save(profile);
        return user;
    }

    private Address base(User customer, String deliveryType) {
        Address address = new Address();
        address.setUser(customer);
        address.setReceiverName("Sok Dara");
        address.setPhone("012345678");
        address.setDeliveryType(com.psar.user.entity.DeliveryType.valueOf(deliveryType));
        return address;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
