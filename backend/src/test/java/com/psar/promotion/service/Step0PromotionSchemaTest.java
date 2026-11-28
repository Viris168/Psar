package com.psar.promotion.service;

import com.psar.catalog.dto.response.SellableProduct;
import com.psar.catalog.entity.Category;
import com.psar.catalog.entity.Product;
import com.psar.catalog.service.ProductCatalogService;
import com.psar.order.entity.Order;
import com.psar.order.entity.OrderStatus;
import com.psar.order.entity.PaymentMethod;
import com.psar.order.service.pricing.PricingCalculator;
import com.psar.order.service.pricing.PricingInput;
import com.psar.order.service.pricing.PricingResult;
import com.psar.promotion.dto.request.PromotionCartLine;
import com.psar.promotion.dto.response.OfferSet;
import com.psar.promotion.entity.DiscountLevel;
import com.psar.promotion.entity.OrderPromotion;
import com.psar.promotion.entity.Promotion;
import com.psar.promotion.entity.PromotionScope;
import com.psar.promotion.entity.PromotionType;
import com.psar.promotion.repository.OrderPromotionRepository;
import com.psar.promotion.repository.PromotionRepository;
import com.psar.support.CatalogFixtures;
import com.psar.support.IntegrationTest;
import com.psar.support.OrderFixtures;
import com.psar.support.PromotionFixtures;
import com.psar.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Proves the Step 0 promotion contract: schema and its CHECKs, the atomic usage update, first versions. */
class Step0PromotionSchemaTest extends IntegrationTest {

    @Autowired
    private CatalogFixtures fixtures;

    @Autowired
    private OrderFixtures orders;

    @Autowired
    private PromotionFixtures promotions;

    @Autowired
    private PromotionRepository promotionRepository;

    @Autowired
    private OrderPromotionRepository orderPromotionRepository;

    @Autowired
    private PromotionService promotionService;

    @Autowired
    private PromotionUsageService usageService;

    @Autowired
    private ProductCatalogService productCatalogService;

    @Autowired
    private PricingCalculator pricingCalculator;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void fixtures_everyType_savedWithTargets() {
        Category parent = fixtures.category("Step0 promo food");
        Product rice = fixtures.product(fixtures.category("Step0 promo rice", parent), "Rice", "5.00", 10, 0);

        Promotion percentage = promotions.percentageOnCategory("10", parent);
        Promotion bxgy = promotions.buyXGetY(2, 1, rice);
        Promotion coupon = promotions.coupon(promotions.uniqueCode("s0"), "5.00", "20.00", 100, 1);

        assertThat(promotionRepository.findWithTargetsById(percentage.getId()).orElseThrow().getTargets()).singleElement()
                .satisfies(t -> assertThat(t.getCategoryId()).isEqualTo(parent.getId()));
        assertThat(jdbc.queryForObject("select count(*) from promotion_targets where promotion_id = ? "
                + "and product_id = ?", Long.class, bxgy.getId(), rice.getId())).isOne();
        assertThat(coupon.getCouponCode()).startsWith("S0").isUpperCase();
        assertThat(promotions.freeDelivery("30.00").getApplyTo()).isEqualTo(PromotionScope.ORDER);
        assertThat(promotions.reload(coupon).getPublicId()).isNotNull();
        assertThat(promotions.reload(coupon).getUsedCount()).isZero();
    }

    @Test
    void checks_invalidPromotionsRejected() {
        Product rice = fixtures.product(fixtures.category("Step0 promo checks"), "Rice", "5.00", 10, 0);

        // buy X get Y without its quantities, free delivery on products, percentage over 100, end before start
        assertThatThrownBy(() -> promotions.save(PromotionType.BUY_X_GET_Y, PromotionScope.PRODUCTS,
                p -> p.addProductTarget(rice.getId()))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> promotions.save(PromotionType.FREE_DELIVERY, PromotionScope.PRODUCTS, p -> { }))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> promotions.save(PromotionType.PERCENTAGE, PromotionScope.ALL,
                p -> p.setDiscountValue(new BigDecimal("100.01"))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> promotions.save(PromotionType.FIXED_AMOUNT, PromotionScope.ORDER, p -> {
            p.setDiscountValue(BigDecimal.ONE);
            p.setEndDate(p.getStartDate());
        })).isInstanceOf(DataIntegrityViolationException.class);

        // a lower-case code can only arrive through SQL; the entity always upper-cases
        Promotion coupon = promotions.coupon(promotions.uniqueCode("chk"), "1.00", null, null, null);
        assertThatThrownBy(() -> jdbc.update("update promotions set coupon_code = lower(coupon_code) where id = ?",
                coupon.getId())).isInstanceOf(DataIntegrityViolationException.class);
        // duplicate code, target with both a product and a category
        assertThatThrownBy(() -> promotions.coupon(coupon.getCouponCode().toLowerCase(), "1.00", null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into promotion_targets (promotion_id, product_id, category_id) "
                        + "values (?, ?, ?)", coupon.getId(), rice.getId(), rice.getCategory().getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void claimUse_limitOne_secondFails_releaseNeverBelowZero() {
        Promotion coupon = promotions.coupon(promotions.uniqueCode("one"), "2.00", null, 1, null);

        assertThat(claim(coupon)).isOne();
        assertThat(claim(coupon)).isZero();
        assertThat(promotions.usedCount(coupon)).isOne();

        assertThat(release(coupon)).isOne();
        assertThat(release(coupon)).isZero();
        assertThat(promotions.usedCount(coupon)).isZero();

        // the DB refuses a used_count over the limit, whatever writes it
        assertThatThrownBy(() -> promotions.setUsedCount(coupon, 2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_staleEntity_doesNotOverwriteUsedCount() {
        Promotion stale = promotions.coupon(promotions.uniqueCode("stale"), "2.00", null, 5, null);
        claim(stale);

        stale.setName("Renamed");
        promotionRepository.save(stale);

        assertThat(promotions.reload(stale).getName()).isEqualTo("Renamed");
        assertThat(promotions.usedCount(stale)).isOne();
    }

    @Test
    void orderPromotion_snapshotSaved_forOrderListsIt() {
        Product rice = fixtures.product(fixtures.category("Step0 promo snap"), "Rice", "5.00", 10, 0);
        User customer = orders.customer();
        Order order = orders.order(customer, orders.cityAddress(customer), PaymentMethod.CASH_ON_DELIVERY,
                OrderStatus.CONFIRMED, Map.of(rice, 3));
        Promotion coupon = promotions.coupon(promotions.uniqueCode("snap"), "2.00", null, null, 1);

        orderPromotionRepository.save(OrderPromotion.of(order.getId(), customer.getId(), coupon, DiscountLevel.ORDER,
                new BigDecimal("2.00"), clock.instant()));

        assertThat(usageService.forOrder(order.getId())).singleElement().satisfies(view -> {
            assertThat(view.promotionName()).isEqualTo(coupon.getName());
            assertThat(view.couponCode()).isEqualTo(coupon.getCouponCode());
            assertThat(view.level()).isEqualTo("ORDER");
            assertThat(view.discountAmount()).isEqualByComparingTo("2.00");
            assertThat(view.released()).isFalse();
        });
        assertThat(orderPromotionRepository.countByPromotionIdAndUserIdAndReleasedAtIsNull(coupon.getId(),
                customer.getId())).isOne();
        // free units are part of quantity
        assertThatThrownBy(() -> jdbc.update("update order_items set free_quantity = 4 where order_id = ?",
                order.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sellable_carriesCategoryAndParent() {
        Category parent = fixtures.category("Step0 promo parent");
        Category child = fixtures.category("Step0 promo child", parent);
        Product inChild = fixtures.product(child, "Noodles", "1.00", 5, 0);
        Product inParent = fixtures.product(parent, "Sauce", "1.00", 5, 0);

        Map<Long, SellableProduct> sellable = productCatalogService.sellable(List.of(inChild.getId(), inParent.getId()));

        assertThat(sellable.get(inChild.getId()).categoryId()).isEqualTo(child.getId());
        assertThat(sellable.get(inChild.getId()).parentCategoryId()).isEqualTo(parent.getId());
        assertThat(sellable.get(inParent.getId()).categoryId()).isEqualTo(parent.getId());
        assertThat(sellable.get(inParent.getId()).parentCategoryId()).isNull();
    }

    /** Until Agents B and C: no offers, prices exactly as in Phase 5. */
    @Test
    void firstVersions_noOffers_pricingIgnoresOffers() {
        OfferSet offers = promotionService.offersFor(1L, List.of(new PromotionCartLine(1L, 2L, null)),
                "  welcome5 ", Instant.now());
        // the offers themselves are real since Agent C (PromotionServiceTest); the shared DB may hold automatic ones
        assertThat(offers.couponCode()).isEqualTo("WELCOME5");

        PricingInput.Offer tenPercent = new PricingInput.Offer(1L, "10%", PricingInput.OfferType.PERCENTAGE,
                PricingInput.OfferLevel.ITEM, new BigDecimal("10"), null, null, null, null, Set.of(1L), false,
                Instant.now());
        PricingResult result = pricingCalculator.price(new PricingInput(
                List.of(new PricingInput.Line(1L, 2, new BigDecimal("5.00"))), new BigDecimal("1.00"),
                List.of(tenPercent)));
        assertThat(result.total()).isEqualByComparingTo("11.00");
        assertThat(result.appliedOffers()).isEmpty();
        assertThat(result.couponStatus()).isEqualTo(PricingResult.CouponStatus.NONE);
        assertThat(result.lines().getFirst().appliedOfferId()).isNull();
    }

    private int claim(Promotion promotion) {
        Integer rows = new TransactionTemplate(transactionManager)
                .execute(s -> promotionRepository.claimUse(promotion.getId()));
        return rows == null ? 0 : rows;
    }

    private int release(Promotion promotion) {
        Integer rows = new TransactionTemplate(transactionManager)
                .execute(s -> promotionRepository.releaseUse(promotion.getId()));
        return rows == null ? 0 : rows;
    }
}
