package com.psar.order.service;

import com.psar.catalog.dto.response.SellableProduct;
import com.psar.catalog.entity.Category;
import com.psar.catalog.entity.Product;
import com.psar.catalog.repository.CategoryRepository;
import com.psar.catalog.service.ProductCatalogService;
import com.psar.common.exception.ConflictException;
import com.psar.common.exception.ErrorCode;
import com.psar.common.exception.NotFoundException;
import com.psar.delivery.entity.DeliveryCompany;
import com.psar.delivery.service.DeliveryCompanyService;
import com.psar.inventory.service.StockService;
import com.psar.order.dto.response.OrderSummary;
import com.psar.order.entity.Order;
import com.psar.order.entity.OrderStatus;
import com.psar.order.entity.PaymentMethod;
import com.psar.order.repository.OrderStatusHistoryRepository;
import com.psar.order.service.delivery.DeliveryQuote;
import com.psar.order.service.delivery.DeliveryQuoteService;
import com.psar.order.service.pricing.PricingCalculator;
import com.psar.order.service.pricing.PricingInput;
import com.psar.order.service.pricing.PricingResult;
import com.psar.payment.entity.PaymentStatus;
import com.psar.payment.repository.PaymentRepository;
import com.psar.support.CatalogFixtures;
import com.psar.support.IntegrationTest;
import com.psar.support.OrderFixtures;
import com.psar.user.dto.response.AddressSnapshot;
import com.psar.user.entity.Address;
import com.psar.user.entity.User;
import com.psar.user.repository.UserRepository;
import com.psar.user.service.AddressLookupService;
import com.psar.user.service.UserDirectoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Proves the Step 0 contract: schema, lookups, reserve / release, pricing and quote first versions. */
class Step0OrderSchemaTest extends IntegrationTest {

    @Autowired
    private CatalogFixtures fixtures;

    @Autowired
    private OrderFixtures orders;

    @Autowired
    private ProductCatalogService productCatalogService;

    @Autowired
    private AddressLookupService addressLookupService;

    @Autowired
    private UserDirectoryService userDirectoryService;

    @Autowired
    private DeliveryCompanyService deliveryCompanyService;

    @Autowired
    private StockService stockService;

    @Autowired
    private OrderLookupService orderLookupService;

    @Autowired
    private DeliveryQuoteService deliveryQuoteService;

    @Autowired
    private PricingCalculator pricingCalculator;

    @Autowired
    private OrderStatusHistoryRepository historyRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void order_savedFromFixture_generatesOrderNoSnapshotHistoryAndPendingPayment() {
        Product product = fixtures.product(fixtures.category("Step0 orders"), "Rice 5kg", "6.50", 10, 2);
        User customer = orders.customer();
        Address address = orders.cityAddress(customer);

        Order first = orders.order(customer, address, PaymentMethod.KHQR, OrderStatus.PENDING_PAYMENT, Map.of(product, 2));
        Order second = orders.order(customer, address, PaymentMethod.CASH_ON_DELIVERY, OrderStatus.CONFIRMED,
                Map.of(product, 1));

        assertThat(first.getOrderNo()).matches("ORD-\\d{6}");
        assertThat(Integer.parseInt(second.getOrderNo().substring(4)))
                .isGreaterThan(Integer.parseInt(first.getOrderNo().substring(4)));
        assertThat(first.getSubtotal()).isEqualByComparingTo("13.00");
        assertThat(first.getTotalAmount()).isEqualByComparingTo("14.00");
        assertThat(first.getExpiresAt()).isAfter(clock.instant().plus(Duration.ofMinutes(14)));
        assertThat(second.getExpiresAt()).isNull();
        assertThat(historyRepository.findAllByOrderIdOrderByIdAsc(first.getId()))
                .extracting(row -> row.getStatus()).containsExactly(OrderStatus.PENDING_PAYMENT);
        assertThat(paymentRepository.findByOrderId(first.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PENDING);
        assertThat(fixtures.quantityOf(product)).isEqualTo(7);

        OrderSummary summary = orderLookupService.summary(first.getId());
        assertThat(summary.orderNo()).isEqualTo(first.getOrderNo());
        assertThat(summary.status()).isEqualTo("PENDING_PAYMENT");
        assertThat(summary.userId()).isEqualTo(customer.getId());
    }

    @Test
    void sellable_inactiveCategory_notPurchasableAndShowsInStock() {
        Category category = fixtures.category("Step0 sellable");
        Product product = fixtures.product(category, "Soy milk", "1.20", 3, 0);
        assertThat(productCatalogService.sellable(product.getPublicId()))
                .satisfies(p -> {
                    assertThat(p.purchasable()).isTrue();
                    assertThat(p.inStock()).isTrue();
                    assertThat(p.sellPrice()).isEqualByComparingTo("1.20");
                });

        category.setActive(false);
        categoryRepository.save(category);

        SellableProduct after = productCatalogService.sellable(List.of(product.getId())).get(product.getId());
        assertThat(after.purchasable()).isFalse();
    }

    @Test
    void requireOwned_otherCustomersAddress_returns404() {
        User owner = orders.customer();
        Address address = orders.cityAddress(owner);

        AddressSnapshot snapshot = addressLookupService.requireOwned(owner.getId(), address.getPublicId());
        assertThat(snapshot.deliveryType()).isEqualTo("CITY");
        assertThat(snapshot.singleLine()).isEqualTo("12, St. 13, Daun Penh, Phnom Penh");
        assertThat(AddressLookupService.insidePhnomPenh(snapshot.latitude(), snapshot.longitude())).isTrue();

        User other = orders.customer();
        assertThatThrownBy(() -> addressLookupService.requireOwned(other.getId(), address.getPublicId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void driverIds_activeStaffWithDriverPosition_only() {
        User driver = orders.driver();
        User inactiveDriver = orders.driver();
        inactiveDriver.setActive(false);
        userRepository.save(inactiveDriver);
        User customer = orders.customer();

        assertThat(userDirectoryService.isActiveDriver(driver.getId())).isTrue();
        assertThat(userDirectoryService.isActiveDriver(inactiveDriver.getId())).isFalse();
        assertThat(userDirectoryService.isActiveDriver(customer.getId())).isFalse();
    }

    @Test
    void reserve_oneLineShort_409AndNoLineReserved() {
        Category category = fixtures.category("Step0 reserve");
        Product plenty = fixtures.product(category, "Plenty", "1.00", 10, 0);
        Product scarce = fixtures.product(category, "Scarce", "1.00", 1, 0);
        Long userId = orders.customer().getId();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                stockService.reserve(Map.of(plenty.getId(), 3, scarce.getId(), 2), null, userId)))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK));

        assertThat(fixtures.quantityOf(plenty)).isEqualTo(10);
        assertThat(fixtures.quantityOf(scarce)).isEqualTo(1);
    }

    @Test
    void release_afterReserve_restoresStock() {
        Product product = fixtures.product(fixtures.category("Step0 release"), "Salt", "0.50", 5, 0);
        Long userId = orders.customer().getId();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(s -> stockService.reserve(Map.of(product.getId(), 4), null, userId));
        assertThat(fixtures.quantityOf(product)).isEqualTo(1);
        tx.executeWithoutResult(s -> stockService.release(Map.of(product.getId(), 4), null, userId));
        assertThat(fixtures.quantityOf(product)).isEqualTo(5);
    }

    @Test
    void quote_step0Versions_cityFlatProvinceBaseFee() {
        User customer = orders.customer();
        DeliveryCompany company = orders.deliveryCompany("2.50");
        AddressSnapshot city = addressLookupService.requireOwned(customer.getId(), orders.cityAddress(customer).getPublicId());
        AddressSnapshot province = addressLookupService.requireOwned(customer.getId(),
                orders.provinceAddress(customer, company).getPublicId());

        assertThat(deliveryQuoteService.quote(city, PaymentMethod.CASH_ON_DELIVERY).fee()).isEqualByComparingTo("1.00");
        DeliveryQuote quote = deliveryQuoteService.quote(province, PaymentMethod.KHQR);
        assertThat(quote.fee()).isEqualByComparingTo("2.50");
        assertThat(quote.company().name()).isEqualTo(company.getName());
        assertThat(deliveryCompanyService.snapshot(company.getId()).trackingUrl()).contains("{tracking_no}");
    }

    @Test
    void price_step0Version_subtotalPlusFee() {
        PricingResult result = pricingCalculator.price(new PricingInput(List.of(
                new PricingInput.Line(1L, 3, new BigDecimal("1.333")),
                new PricingInput.Line(2L, 1, new BigDecimal("2.50"))), new BigDecimal("1")));

        assertThat(result.lines()).extracting(PricingResult.Line::lineTotal)
                .containsExactly(new BigDecimal("4.00"), new BigDecimal("2.50"));
        assertThat(result.subtotal()).isEqualByComparingTo("6.50");
        assertThat(result.deliveryFee()).isEqualByComparingTo("1.00");
        assertThat(result.total()).isEqualByComparingTo("7.50");
    }
}
