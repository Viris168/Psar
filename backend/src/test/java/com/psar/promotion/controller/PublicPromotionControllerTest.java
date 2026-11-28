package com.psar.promotion.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.psar.catalog.entity.Category;
import com.psar.catalog.entity.Product;
import com.psar.promotion.entity.Promotion;
import com.psar.promotion.entity.PromotionScope;
import com.psar.promotion.entity.PromotionType;
import com.psar.support.CatalogFixtures;
import com.psar.support.IntegrationTest;
import com.psar.support.PromotionFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/public/promotions: running automatic promotions only, nothing about coupons or usage. */
class PublicPromotionControllerTest extends IntegrationTest {

    private static final String PUBLIC = "/api/public/promotions";

    @Autowired
    private CatalogFixtures catalog;
    @Autowired
    private PromotionFixtures promotions;

    @Test
    void publicPromotions_hidesCouponsAndInactive() throws Exception {
        String tag = "pub-" + UUID.randomUUID().toString().substring(0, 8);
        Category category = catalog.category("Pub cat " + tag);
        Product product = catalog.product(category, "Pub product", "10.00", 5, 1);
        Instant soon = clock.instant().plus(Duration.ofHours(2)); // ends soon: near the top of the list

        Promotion shown = save(PromotionType.BUY_X_GET_Y, PromotionScope.PRODUCTS, tag + " shown", soon, p -> {
            p.setBuyQuantity(2);
            p.setGetQuantity(1);
            p.setDescription("Buy two, get one");
            p.addProductTarget(product.getId());
        });
        Promotion onCategory = save(PromotionType.PERCENTAGE, PromotionScope.CATEGORIES, tag + " category", soon,
                p -> {
                    p.setDiscountValue(new BigDecimal("10"));
                    p.addCategoryTarget(category.getId());
                });
        save(PromotionType.FIXED_AMOUNT, PromotionScope.ORDER, tag + " coupon", soon, p -> {
            p.setDiscountValue(BigDecimal.ONE);
            p.setCouponCode(promotions.uniqueCode("PUB"));
            p.setUsageLimit(5);
        });
        save(PromotionType.FREE_DELIVERY, PromotionScope.ORDER, tag + " inactive", soon, p -> p.setActive(false));
        save(PromotionType.FREE_DELIVERY, PromotionScope.ORDER, tag + " members", soon, p -> p.setMembersOnly(true));
        save(PromotionType.FREE_DELIVERY, PromotionScope.ORDER, tag + " scheduled", soon,
                p -> p.setStartDate(clock.instant().plus(Duration.ofHours(1))));
        save(PromotionType.FREE_DELIVERY, PromotionScope.ORDER, tag + " ended", soon, p -> {
            p.setStartDate(clock.instant().minus(Duration.ofDays(3)));
            p.setEndDate(clock.instant().minus(Duration.ofDays(1)));
        });

        JsonNode content = objectMapper.readTree(mockMvc.perform(get(PUBLIC).param("size", "100"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("content");

        List<JsonNode> mine = new ArrayList<>();
        content.forEach(node -> {
            if (node.get("name").asText().startsWith(tag)) {
                mine.add(node);
            }
        });
        assertThat(mine).extracting(n -> n.get("name").asText())
                .containsExactlyInAnyOrder(shown.getName(), onCategory.getName());

        JsonNode bxgy = mine.stream().filter(n -> n.get("name").asText().equals(shown.getName())).findFirst()
                .orElseThrow();
        assertThat(bxgy.get("buyQuantity").asInt()).isEqualTo(2);
        assertThat(bxgy.get("description").asText()).isEqualTo("Buy two, get one");
        assertThat(bxgy.get("productSlugs")).extracting(JsonNode::asText).containsExactly(product.getSlug());
        JsonNode cat = mine.stream().filter(n -> n.get("name").asText().equals(onCategory.getName())).findFirst()
                .orElseThrow();
        assertThat(cat.get("categorySlugs")).extracting(JsonNode::asText).containsExactly(category.getSlug());

        // never coupon codes, limits, usage or internal ids, in any row
        content.forEach(node -> assertThat(node.has("couponCode") || node.has("usageLimit")
                || node.has("perUserLimit") || node.has("usedCount") || node.has("id")).isFalse());
        assertThat(objectMapper.convertValue(bxgy, Map.class)).containsKey("publicId");
    }

    private Promotion save(PromotionType type, PromotionScope scope, String name, Instant end,
                           Consumer<Promotion> customize) {
        return promotions.save(type, scope, p -> {
            p.setName(name);
            p.setEndDate(end);
            customize.accept(p);
        });
    }
}
