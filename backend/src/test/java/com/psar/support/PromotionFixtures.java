package com.psar.support;

import com.psar.catalog.entity.Category;
import com.psar.catalog.entity.Product;
import com.psar.order.entity.Order;
import com.psar.promotion.entity.OrderPromotion;
import com.psar.promotion.entity.Promotion;
import com.psar.promotion.entity.PromotionScope;
import com.psar.promotion.entity.PromotionType;
import com.psar.promotion.repository.OrderPromotionRepository;
import com.psar.promotion.repository.PromotionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Promotions and coupons for tests. Every promotion is active, started an hour ago (test clock) and ends in 30
 * days unless {@code customize} changes it; created by a new manager.
 * Available in every IntegrationTest: {@code @Autowired PromotionFixtures promotions;}
 * <p>
 * Test setup only: real code takes and gives back uses through PromotionUsageService. {@link #setUsedCount}
 * writes {@code used_count} directly (e.g. "already used up").
 */
@TestComponent
@RequiredArgsConstructor
public class PromotionFixtures {

    private final PromotionRepository promotionRepository;
    private final OrderPromotionRepository orderPromotionRepository;
    private final CatalogFixtures catalog;
    private final Clock clock;
    private final JdbcTemplate jdbc;

    // ---- automatic item promotions -----------------------------------------------------------------

    /** {@code percent}% off these products' lines. */
    public Promotion percentageOnProducts(String percent, Product... products) {
        return save(PromotionType.PERCENTAGE, PromotionScope.PRODUCTS, p -> {
            p.setDiscountValue(new BigDecimal(percent));
            Arrays.stream(products).forEach(product -> p.addProductTarget(product.getId()));
        });
    }

    /** {@code percent}% off every product in the category (and its subcategories, if it is top-level). */
    public Promotion percentageOnCategory(String percent, Category category) {
        return save(PromotionType.PERCENTAGE, PromotionScope.CATEGORIES, p -> {
            p.setDiscountValue(new BigDecimal(percent));
            p.addCategoryTarget(category.getId());
        });
    }

    /** {@code percent}% off every line in the shop. */
    public Promotion percentageOnAll(String percent) {
        return save(PromotionType.PERCENTAGE, PromotionScope.ALL, p -> p.setDiscountValue(new BigDecimal(percent)));
    }

    /** {@code amount} off each unit of these products. */
    public Promotion fixedOnProducts(String amount, Product... products) {
        return save(PromotionType.FIXED_AMOUNT, PromotionScope.PRODUCTS, p -> {
            p.setDiscountValue(new BigDecimal(amount));
            Arrays.stream(products).forEach(product -> p.addProductTarget(product.getId()));
        });
    }

    /** Buy {@code buy} get {@code get} free, per line, on these products. */
    public Promotion buyXGetY(int buy, int get, Product... products) {
        return save(PromotionType.BUY_X_GET_Y, PromotionScope.PRODUCTS, p -> {
            p.setBuyQuantity(buy);
            p.setGetQuantity(get);
            Arrays.stream(products).forEach(product -> p.addProductTarget(product.getId()));
        });
    }

    // ---- automatic order promotions ----------------------------------------------------------------

    /** {@code percent}% off the order, from {@code minOrder} (null = any order). */
    public Promotion orderPercentage(String percent, String minOrder) {
        return save(PromotionType.PERCENTAGE, PromotionScope.ORDER, p -> {
            p.setDiscountValue(new BigDecimal(percent));
            p.setMinOrderAmount(minOrder == null ? null : new BigDecimal(minOrder));
        });
    }

    /** Free delivery from {@code minOrder} (null = any order). */
    public Promotion freeDelivery(String minOrder) {
        return save(PromotionType.FREE_DELIVERY, PromotionScope.ORDER,
                p -> p.setMinOrderAmount(minOrder == null ? null : new BigDecimal(minOrder)));
    }

    // ---- coupons -----------------------------------------------------------------------------------

    /**
     * Coupon {@code code}: {@code amount} off the order from {@code minOrder} (null = any), at most
     * {@code usageLimit} uses in total and {@code perUserLimit} per customer (null = unlimited).
     */
    public Promotion coupon(String code, String amount, String minOrder, Integer usageLimit, Integer perUserLimit) {
        return save(PromotionType.FIXED_AMOUNT, PromotionScope.ORDER, p -> {
            p.setCouponCode(code);
            p.setDiscountValue(new BigDecimal(amount));
            p.setMinOrderAmount(minOrder == null ? null : new BigDecimal(minOrder));
            p.setUsageLimit(usageLimit);
            p.setPerUserLimit(perUserLimit);
        });
    }

    /** A code nobody else uses: {@code prefix} + 6 random characters, upper-case. */
    public String uniqueCode(String prefix) {
        return (prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 6)).toUpperCase();
    }

    // ---- anything else -----------------------------------------------------------------------------

    /**
     * Any promotion: defaults as in the class comment, then {@code customize} (dates, limits, code, targets,
     * members only, inactive, …).
     */
    public Promotion save(PromotionType type, PromotionScope applyTo, Consumer<Promotion> customize) {
        Promotion promotion = new Promotion();
        promotion.setName(type.name().toLowerCase() + " " + UUID.randomUUID().toString().substring(0, 8));
        promotion.setType(type);
        promotion.setApplyTo(applyTo);
        promotion.setStartDate(clock.instant().minus(Duration.ofHours(1)));
        promotion.setEndDate(clock.instant().plus(Duration.ofDays(30)));
        promotion.setCreatedBy(catalog.manager().getId());
        customize.accept(promotion);
        return promotionRepository.save(promotion);
    }

    /** Reloaded, with its current {@code usedCount}. */
    public Promotion reload(Promotion promotion) {
        return promotionRepository.findById(promotion.getId()).orElseThrow();
    }

    public int usedCount(Promotion promotion) {
        return reload(promotion).getUsedCount();
    }

    /** Test setup only: sets {@code used_count} directly (must stay within the usage limit). */
    public void setUsedCount(Promotion promotion, int usedCount) {
        jdbc.update("update promotions set used_count = ? where id = ?", usedCount, promotion.getId());
    }

    public List<OrderPromotion> orderPromotions(Order order) {
        return orderPromotionRepository.findAllByOrderIdOrderByIdAsc(order.getId());
    }
}
