package com.psar.promotion.service;

import com.psar.catalog.dto.response.SellableProduct;
import com.psar.catalog.entity.Category;
import com.psar.catalog.entity.Product;
import com.psar.catalog.service.ProductCatalogService;
import com.psar.common.exception.ConflictException;
import com.psar.common.exception.ErrorCode;
import com.psar.order.entity.Order;
import com.psar.order.entity.OrderStatus;
import com.psar.order.entity.PaymentMethod;
import com.psar.promotion.dto.request.PromotionCartLine;
import com.psar.promotion.dto.request.UsedPromotion;
import com.psar.promotion.dto.response.OfferSet;
import com.psar.promotion.dto.response.PromotionOffer;
import com.psar.promotion.entity.OrderPromotion;
import com.psar.promotion.entity.Promotion;
import com.psar.promotion.entity.PromotionScope;
import com.psar.promotion.entity.PromotionType;
import com.psar.promotion.repository.PromotionRepository;
import com.psar.support.CatalogFixtures;
import com.psar.support.IntegrationTest;
import com.psar.support.OrderFixtures;
import com.psar.support.PromotionFixtures;
import com.psar.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Eligibility ({@code offersFor}) and usage ({@code claim} / {@code release}) without pricing. Only
 * product-targeted automatic promotions are created here: the database is shared by all tests, so an automatic
 * ALL / ORDER promotion would change other tests' totals.
 */
class PromotionServiceTest extends IntegrationTest {

    @Autowired
    private PromotionService promotionService;

    @Autowired
    private PromotionUsageService usageService;

    @Autowired
    private PromotionFixtures promotions;

    @Autowired
    private CatalogFixtures catalog;

    @Autowired
    private OrderFixtures orders;

    @Autowired
    private PromotionRepository promotionRepository;

    @Autowired
    private ProductCatalogService productCatalogService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // ---- offersFor: automatic ----------------------------------------------------------------------

    @Test
    void offersFor_productAndCategoryTargets_listOnlyMatchingCartProducts() {
        Category parent = catalog.category("Offers parent");
        Category child = catalog.category("Offers child", parent);
        Product inChild = catalog.product(child, "Child tea", "2.00", 10, 0);
        Product inParent = catalog.product(parent, "Parent tea", "3.00", 10, 0);
        Product other = catalog.product(catalog.category("Offers other"), "Other soap", "1.00", 10, 0);
        Promotion onParent = promotions.percentageOnCategory("10", parent);
        Promotion onChild = promotions.percentageOnCategory("5", child);
        Promotion onOther = promotions.fixedOnProducts("0.50", other);
        Promotion bxgy = promotions.buyXGetY(2, 1, inParent);

        OfferSet offers = offersFor(orders.customer(), null, inChild, inParent);

        assertThat(offer(offers, onParent)).hasValueSatisfying(o -> {
            assertThat(o.productIds()).containsExactlyInAnyOrder(inChild.getId(), inParent.getId());
            assertThat(o.level()).isEqualTo("ITEM");
            assertThat(o.type()).isEqualTo("PERCENTAGE");
            assertThat(o.value()).isEqualByComparingTo("10");
            assertThat(o.isCoupon()).isFalse();
            assertThat(o.endsAt()).isCloseTo(onParent.getEndDate(), within(1, ChronoUnit.MILLIS));
        });
        assertThat(offer(offers, onChild)).hasValueSatisfying(o ->
                assertThat(o.productIds()).containsExactly(inChild.getId()));
        assertThat(offer(offers, bxgy)).hasValueSatisfying(o -> {
            assertThat(o.buyQuantity()).isEqualTo(2);
            assertThat(o.getQuantity()).isEqualTo(1);
        });
        assertThat(offer(offers, onOther)).isEmpty();
        assertThat(offers.couponCode()).isNull();
        assertThat(offers.couponProblem()).isNull();
    }

    @Test
    void offersFor_inactiveScheduledEndedUsedUpMembersOnly_skipped() {
        Product product = catalog.product(catalog.category("Offers skipped"), "Skipped rice", "5.00", 10, 0);
        Promotion inactive = productPromotion(product, p -> p.setActive(false));
        Promotion scheduled = productPromotion(product, p -> {
            p.setStartDate(clock.instant().plus(Duration.ofHours(1)));
            p.setEndDate(clock.instant().plus(Duration.ofDays(1)));
        });
        Promotion ended = productPromotion(product, p -> {
            p.setStartDate(clock.instant().minus(Duration.ofDays(2)));
            p.setEndDate(clock.instant().minus(Duration.ofSeconds(1)));
        });
        Promotion usedUp = productPromotion(product, p -> p.setUsageLimit(2));
        promotions.setUsedCount(usedUp, 2);
        Promotion membersOnly = productPromotion(product, p -> p.setMembersOnly(true));
        Promotion running = productPromotion(product, p -> p.setUsageLimit(2));
        promotions.setUsedCount(running, 1);

        OfferSet offers = offersFor(orders.customer(), null, product);

        assertThat(offers.offers()).extracting(PromotionOffer::id)
                .contains(running.getId())
                .doesNotContain(inactive.getId(), scheduled.getId(), ended.getId(), usedUp.getId(),
                        membersOnly.getId());
    }

    @Test
    void offersFor_perUserLimitReached_skippedForThatUserOnly() {
        Product product = catalog.product(catalog.category("Offers per user"), "Per-user milk", "2.00", 20, 0);
        Promotion oncePerUser = productPromotion(product, p -> p.setPerUserLimit(1));
        User used = orders.customer();
        claimOn(used, product, oncePerUser);

        assertThat(offer(offersFor(used, null, product), oncePerUser)).isEmpty();
        assertThat(offer(offersFor(orders.customer(), null, product), oncePerUser)).isPresent();
    }

    // ---- offersFor: coupons ------------------------------------------------------------------------

    @Test
    void offersFor_validCoupon_caseInsensitiveOffer() {
        Product product = catalog.product(catalog.category("Coupon ok"), "Coupon noodles", "4.00", 10, 0);
        String code = promotions.uniqueCode("OK");
        Promotion coupon = promotions.coupon(code, "5.00", "20.00", 10, 1);

        OfferSet offers = offersFor(orders.customer(), "  " + code.toLowerCase() + " ", product);

        assertThat(offers.couponCode()).isEqualTo(code);
        assertThat(offers.couponProblem()).isNull();
        assertThat(offers.coupon()).hasValueSatisfying(o -> {
            assertThat(o.id()).isEqualTo(coupon.getId());
            assertThat(o.couponCode()).isEqualTo(code);
            assertThat(o.level()).isEqualTo("ORDER");
            assertThat(o.productIds()).isEmpty();
            assertThat(o.minOrderAmount()).isEqualByComparingTo("20.00");
        });
    }

    @Test
    void offersFor_unusableCoupons_problemAndNoOffer() {
        Product product = catalog.product(catalog.category("Coupon problems"), "Problem rice", "4.00", 10, 0);
        Product elsewhere = catalog.product(catalog.category("Coupon elsewhere"), "Elsewhere oil", "4.00", 10, 0);
        User customer = orders.customer();

        assertProblem(customer, "nope-" + promotions.uniqueCode(""), product, "is not valid");
        assertProblem(customer, couponCode(p -> p.setActive(false)), product, "is not valid");
        assertProblem(customer, couponCode(p -> {
            p.setStartDate(clock.instant().plus(Duration.ofDays(1)));
            p.setEndDate(clock.instant().plus(Duration.ofDays(2)));
        }), product, "can't be used yet");
        assertProblem(customer, couponCode(p -> {
            p.setStartDate(clock.instant().minus(Duration.ofDays(2)));
            p.setEndDate(clock.instant().minus(Duration.ofDays(1)));
        }), product, "has expired");
        assertProblem(customer, couponCode(p -> p.setMembersOnly(true)), product, "is for members only");

        String usedUp = couponCode(p -> p.setUsageLimit(1));
        promotions.setUsedCount(promotionRepository.findByCouponCode(usedUp).orElseThrow(), 1);
        assertProblem(customer, usedUp, product, "has been fully used");

        String onElsewhere = promotions.uniqueCode("ITEM");
        promotions.save(PromotionType.PERCENTAGE, PromotionScope.PRODUCTS, p -> {
            p.setCouponCode(onElsewhere);
            p.setDiscountValue(new BigDecimal("10"));
            p.addProductTarget(elsewhere.getId());
        });
        assertProblem(customer, onElsewhere, product, "doesn't apply to any product in your cart");
        assertThat(offersFor(customer, onElsewhere, product, elsewhere).coupon()).hasValueSatisfying(o ->
                assertThat(o.productIds()).containsExactly(elsewhere.getId()));
    }

    @Test
    void offersFor_couponPerUserLimitReached_problemForThatUserOnly() {
        Product product = catalog.product(catalog.category("Coupon per user"), "Per-user coffee", "4.00", 10, 0);
        String code = promotions.uniqueCode("ONCE");
        Promotion coupon = promotions.coupon(code, "1.00", null, null, 1);
        User used = orders.customer();
        claimOn(used, product, coupon);

        assertProblem(used, code, product, "You have already used coupon " + code);
        assertThat(offersFor(orders.customer(), code, product).coupon()).isPresent();
    }

    // ---- claim / release ---------------------------------------------------------------------------

    @Test
    void claim_snapshotsRowsAndCountsUse() {
        Product product = catalog.product(catalog.category("Claim rows"), "Claim beans", "3.00", 10, 0);
        String code = promotions.uniqueCode("SNAP");
        Promotion coupon = promotions.coupon(code, "2.00", null, 5, null);
        Promotion automatic = productPromotion(product, p -> { });
        User customer = orders.customer();
        Order order = order(customer, product);

        inTransaction(() -> usageService.claim(order.getId(), customer.getId(), List.of(
                new UsedPromotion(coupon.getId(), "ORDER", new BigDecimal("2.00")),
                new UsedPromotion(automatic.getId(), "ITEM", new BigDecimal("0.60")))));

        assertThat(promotions.usedCount(coupon)).isEqualTo(1);
        assertThat(promotions.usedCount(automatic)).isEqualTo(1);
        assertThat(promotions.orderPromotions(order)).hasSize(2).allSatisfy(row -> {
            assertThat(row.getUserId()).isEqualTo(customer.getId());
            assertThat(row.isReleased()).isFalse();
        });
        OrderPromotion couponRow = promotions.orderPromotions(order).stream()
                .filter(row -> row.getPromotionId().equals(coupon.getId())).findFirst().orElseThrow();
        assertThat(couponRow.getCouponCode()).isEqualTo(code);
        assertThat(couponRow.getPromotionName()).isEqualTo(coupon.getName());
        assertThat(couponRow.getDiscountAmount()).isEqualByComparingTo("2.00");
    }

    @Test
    void claim_usedUp_409RollsBack() {
        Product product = catalog.product(catalog.category("Claim used up"), "Used-up beans", "3.00", 10, 0);
        Promotion fine = productPromotion(product, p -> { });
        String code = promotions.uniqueCode("FULL");
        Promotion coupon = promotions.coupon(code, "1.00", null, 1, null);
        promotions.setUsedCount(coupon, 1);
        User customer = orders.customer();
        Order order = order(customer, product);

        assertThatThrownBy(() -> inTransaction(() -> usageService.claim(order.getId(), customer.getId(), List.of(
                new UsedPromotion(fine.getId(), "ITEM", new BigDecimal("0.30")),
                new UsedPromotion(coupon.getId(), "ORDER", new BigDecimal("1.00"))))))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROMOTION_EXHAUSTED);
                    assertThat(e.getMessage()).contains("Coupon " + code);
                });

        assertThat(promotions.usedCount(fine)).isZero();
        assertThat(promotions.usedCount(coupon)).isEqualTo(1);
        assertThat(promotions.orderPromotions(order)).isEmpty();
    }

    @Test
    void claim_outsideTransaction_rejected() {
        assertThatThrownBy(() -> usageService.claim(1L, 1L, List.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> usageService.release(1L))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void release_twice_givesUseBackOnce() {
        Product product = catalog.product(catalog.category("Release once"), "Release beans", "3.00", 10, 0);
        String code = promotions.uniqueCode("BACK");
        Promotion coupon = promotions.coupon(code, "1.00", null, 3, null);
        User customer = orders.customer();
        Order first = claimOn(customer, product, coupon);
        claimOn(orders.customer(), product, coupon);
        assertThat(promotions.usedCount(coupon)).isEqualTo(2);

        inTransaction(() -> usageService.release(first.getId()));
        inTransaction(() -> usageService.release(first.getId()));
        inTransaction(() -> usageService.release(-1L)); // no promotions: nothing happens

        assertThat(promotions.usedCount(coupon)).isEqualTo(1);
        assertThat(promotions.orderPromotions(first)).singleElement().satisfies(row -> {
            assertThat(row.isReleased()).isTrue();
            assertThat(row.getReleasedAt()).isBefore(clock.instant().plusSeconds(1));
        });
        assertThat(usageService.forOrder(first.getId())).singleElement()
                .satisfies(view -> assertThat(view.released()).isTrue());
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private OfferSet offersFor(User customer, String code, Product... products) {
        List<Long> ids = Arrays.stream(products).map(Product::getId).toList();
        Map<Long, SellableProduct> sellable = productCatalogService.sellable(ids);
        List<PromotionCartLine> lines = ids.stream().map(sellable::get)
                .map(p -> new PromotionCartLine(p.id(), p.categoryId(), p.parentCategoryId()))
                .toList();
        return promotionService.offersFor(customer.getId(), lines, code, clock.instant());
    }

    private static Optional<PromotionOffer> offer(OfferSet offers, Promotion promotion) {
        return offers.offers().stream().filter(o -> o.id().equals(promotion.getId())).findFirst();
    }

    private void assertProblem(User customer, String code, Product product, String problem) {
        OfferSet offers = offersFor(customer, code, product);
        assertThat(offers.coupon()).as(code).isEmpty();
        assertThat(offers.couponProblem()).as(code).contains(problem);
    }

    private Promotion productPromotion(Product product, Consumer<Promotion> customize) {
        return promotions.save(PromotionType.PERCENTAGE, PromotionScope.PRODUCTS, p -> {
            p.setDiscountValue(new BigDecimal("10"));
            p.addProductTarget(product.getId());
            customize.accept(p);
        });
    }

    private String couponCode(Consumer<Promotion> customize) {
        String code = promotions.uniqueCode("BAD");
        promotions.save(PromotionType.FIXED_AMOUNT, PromotionScope.ORDER, p -> {
            p.setCouponCode(code);
            p.setDiscountValue(new BigDecimal("1.00"));
            customize.accept(p);
        });
        return code;
    }

    private Order order(User customer, Product product) {
        return orders.order(customer, orders.cityAddress(customer), PaymentMethod.CASH_ON_DELIVERY,
                OrderStatus.CONFIRMED, Map.of(product, 1));
    }

    /** An order for this customer that used {@code promotion} (claimed as checkout would). */
    private Order claimOn(User customer, Product product, Promotion promotion) {
        Order order = order(customer, product);
        String level = promotion.getApplyTo().isItemLevel() ? "ITEM" : "ORDER";
        inTransaction(() -> usageService.claim(order.getId(), customer.getId(),
                List.of(new UsedPromotion(promotion.getId(), level, new BigDecimal("1.00")))));
        return order;
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }
}
