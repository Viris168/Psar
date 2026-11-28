package com.psar.promotion.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.psar.audit.entity.AuditAction;
import com.psar.audit.entity.AuditLog;
import com.psar.audit.service.AuditEntity;
import com.psar.catalog.entity.Category;
import com.psar.catalog.entity.Product;
import com.psar.notification.entity.Notification;
import com.psar.notification.entity.NotificationType;
import com.psar.promotion.entity.Promotion;
import com.psar.promotion.entity.PromotionScope;
import com.psar.promotion.entity.PromotionType;
import com.psar.support.CatalogFixtures;
import com.psar.support.IntegrationTest;
import com.psar.support.PromotionFixtures;
import com.psar.support.SystemFixtures;
import com.psar.support.TestAuth;
import com.psar.user.entity.Role;
import com.psar.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/admin/promotions: CRUD, validation, frozen terms after use, status, broadcast, roles. */
class AdminPromotionControllerTest extends IntegrationTest {

    private static final String ADMIN = "/api/admin/promotions";

    @Autowired
    private CatalogFixtures catalog;
    @Autowired
    private PromotionFixtures promotions;
    @Autowired
    private SystemFixtures system;

    private User manager;

    @BeforeEach
    void setUp() {
        manager = system.user(Role.MANAGER);
    }

    // ---- create + validation -------------------------------------------------------------------

    @Test
    void createPromotion_eachTypeValidated() throws Exception {
        Category category = catalog.category("Promo cat " + shortId());
        Product product = catalog.product(category, "Promo product", "10.00", 5, 1);

        // valid: one of each type
        create(body("PERCENTAGE", "CATEGORIES", b -> {
            b.put("discountValue", "15");
            b.put("maxDiscount", "5");
            b.put("categorySlugs", List.of(category.getSlug()));
        })).andExpect(status().isCreated())
                .andExpect(jsonPath("$.discountValue").value(15.00))
                .andExpect(jsonPath("$.categories[0].slug").value(category.getSlug()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.usedCount").value(0));
        create(body("FIXED_AMOUNT", "PRODUCTS", b -> {
            b.put("discountValue", "1.50");
            b.put("productIds", List.of(product.getPublicId()));
        })).andExpect(status().isCreated())
                .andExpect(jsonPath("$.products[0].publicId").value(product.getPublicId().toString()))
                .andExpect(jsonPath("$.products[0].slug").value(product.getSlug()));
        create(body("BUY_X_GET_Y", "ALL", b -> {
            b.put("buyQuantity", 2);
            b.put("getQuantity", 1);
        })).andExpect(status().isCreated());
        create(body("FREE_DELIVERY", "ORDER", b -> b.put("minOrderAmount", "30")))
                .andExpect(status().isCreated());

        // invalid ones: 422 with the field that is wrong
        expect422(body("PERCENTAGE", "ALL", b -> b.put("discountValue", "0.5")), "discountValue");
        expect422(body("PERCENTAGE", "ALL", b -> b.put("discountValue", "101")), "discountValue");
        expect422(body("PERCENTAGE", "ALL", b -> { }), "discountValue");
        expect422(body("FIXED_AMOUNT", "ORDER", b -> b.put("discountValue", "0")), "discountValue");
        expect422(body("FIXED_AMOUNT", "ORDER", b -> {
            b.put("discountValue", "5");
            b.put("maxDiscount", "2");
        }), "maxDiscount");
        expect422(body("BUY_X_GET_Y", "ALL", b -> {
            b.put("buyQuantity", 0);
            b.put("getQuantity", 1);
        }), "buyQuantity");
        expect422(body("BUY_X_GET_Y", "ALL", b -> b.put("buyQuantity", 2)), "getQuantity");
        expect422(body("BUY_X_GET_Y", "ORDER", b -> {
            b.put("buyQuantity", 2);
            b.put("getQuantity", 1);
        }), "applyTo");
        expect422(body("FREE_DELIVERY", "ALL", b -> { }), "applyTo");
        expect422(body("PERCENTAGE", "PRODUCTS", b -> b.put("discountValue", "10")), "productIds");
        expect422(body("PERCENTAGE", "PRODUCTS", b -> {
            b.put("discountValue", "10");
            b.put("productIds", List.of(UUID.randomUUID()));
        }), "productIds");
        expect422(body("PERCENTAGE", "CATEGORIES", b -> {
            b.put("discountValue", "10");
            b.put("categorySlugs", List.of("no-such-category-" + shortId()));
        }), "categorySlugs");
        expect422(body("PERCENTAGE", "ALL", b -> {
            b.put("discountValue", "10");
            b.put("productIds", List.of(product.getPublicId()));
        }), "productIds");
        expect422(body("FIXED_AMOUNT", "ORDER", b -> {
            b.put("discountValue", "5");
            b.put("couponCode", "no spaces!");
        }), "couponCode");
        expect422(body("FIXED_AMOUNT", "ORDER", b -> {
            b.put("discountValue", "5");
            b.put("couponCode", "AB");
        }), "couponCode");
        expect422(body("FIXED_AMOUNT", "ORDER", b -> {
            b.put("discountValue", "5");
            b.put("endDate", b.get("startDate"));
        }), "endDate");
    }

    @Test
    void createPromotion_couponCode_storedUpperCaseAndAudited() throws Exception {
        String code = promotions.uniqueCode("save");
        JsonNode created = read(create(body("FIXED_AMOUNT", "ORDER", b -> {
            b.put("discountValue", "5");
            b.put("couponCode", "  " + code.toLowerCase() + " ");
            b.put("usageLimit", 10);
            b.put("perUserLimit", 1);
        })).andExpect(status().isCreated())
                .andExpect(jsonPath("$.couponCode").value(code))
                .andExpect(jsonPath("$.usageLimit").value(10)));

        List<AuditLog> rows = system.auditRows(AuditEntity.PROMOTION, created.get("publicId").asText());
        assertThat(rows).extracting(AuditLog::getAction).containsExactly(AuditAction.CREATE);
        assertThat(rows.getFirst().getUserId()).isEqualTo(manager.getId());
    }

    @Test
    void createPromotion_duplicateCoupon_409() throws Exception {
        String code = promotions.uniqueCode("dup");
        promotions.coupon(code, "5", null, null, null);

        create(body("PERCENTAGE", "ORDER", b -> {
            b.put("discountValue", "10");
            b.put("couponCode", code.toLowerCase());
        })).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString(code)));
    }

    // ---- update --------------------------------------------------------------------------------

    @Test
    void updatePromotion_unused_changesTermsAndTargets() throws Exception {
        Category category = catalog.category("Upd cat " + shortId());
        Product a = catalog.product(category, "A", "10.00", 5, 1);
        Product b = catalog.product(category, "B", "10.00", 5, 1);
        Promotion promotion = promotions.percentageOnProducts("10", a);

        update(promotion, body("PERCENTAGE", "PRODUCTS", m -> {
            m.put("name", "Renamed");
            m.put("discountValue", "20");
            m.put("productIds", List.of(a.getPublicId(), b.getPublicId()));
        })).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.discountValue").value(20.00))
                .andExpect(jsonPath("$.products[*].slug", containsInAnyOrder(a.getSlug(), b.getSlug())));

        update(promotion, body("PERCENTAGE", "PRODUCTS", m -> {
            m.put("discountValue", "20");
            m.put("productIds", List.of(b.getPublicId()));
        })).andExpect(status().isOk())
                .andExpect(jsonPath("$.products.length()").value(1))
                .andExpect(jsonPath("$.products[0].slug").value(b.getSlug()));

        assertThat(system.auditRows(AuditEntity.PROMOTION, promotion.getPublicId().toString()))
                .extracting(AuditLog::getAction).containsExactly(AuditAction.UPDATE, AuditAction.UPDATE);
    }

    @Test
    void updatePromotion_afterUse_409() throws Exception {
        String code = promotions.uniqueCode("used");
        Promotion promotion = promotions.coupon(code, "5.00", null, 10, null);
        promotions.setUsedCount(promotion, 3);

        // terms frozen: value, type, coupon code
        update(promotion, couponBody(code, "6.00", 10)).andExpect(status().isConflict());
        update(promotion, couponBody(code + "X", "5.00", 10)).andExpect(status().isConflict());
        update(promotion, body("PERCENTAGE", "ORDER", m -> {
            m.put("discountValue", "5");
            m.put("couponCode", code);
            m.put("usageLimit", 10);
        })).andExpect(status().isConflict());

        // usage limit can't go below what's used
        expect422(update(promotion, couponBody(code, "5.00", 2)), "usageLimit");

        // everything else may still change; used_count is untouched
        update(promotion, couponBody(code, "5", 3).andName("Still fine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Still fine"))
                .andExpect(jsonPath("$.usageLimit").value(3))
                .andExpect(jsonPath("$.usedCount").value(3));
        assertThat(promotions.usedCount(promotion)).isEqualTo(3);
    }

    @Test
    void deactivatePromotion_soft_inactiveAndAudited() throws Exception {
        Promotion promotion = promotions.percentageOnAll("5");

        mockMvc.perform(delete(ADMIN + "/" + promotion.getPublicId()).with(TestAuth.as(manager)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(ADMIN + "/" + promotion.getPublicId()).with(TestAuth.as(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.status").value("INACTIVE"));
        assertThat(promotions.reload(promotion).isActive()).isFalse();
        assertThat(system.auditRows(AuditEntity.PROMOTION, promotion.getPublicId().toString()))
                .extracting(AuditLog::getAction).containsExactly(AuditAction.UPDATE);
    }

    @Test
    void getPromotion_unknown_404() throws Exception {
        mockMvc.perform(get(ADMIN + "/" + UUID.randomUUID()).with(TestAuth.as(manager)))
                .andExpect(status().isNotFound());
    }

    // ---- list ----------------------------------------------------------------------------------

    @Test
    void listPromotions_statusFromDates() throws Exception {
        String tag = "status-" + shortId();
        Instant now = clock.instant();
        promotions.save(PromotionType.FREE_DELIVERY, PromotionScope.ORDER, p -> p.setName(tag + " active"));
        promotions.save(PromotionType.FREE_DELIVERY, PromotionScope.ORDER, p -> {
            p.setName(tag + " scheduled");
            p.setStartDate(now.plus(Duration.ofDays(1)));
        });
        promotions.save(PromotionType.FREE_DELIVERY, PromotionScope.ORDER, p -> {
            p.setName(tag + " ended");
            p.setStartDate(now.minus(Duration.ofDays(10)));
            p.setEndDate(now.minus(Duration.ofDays(1)));
        });
        promotions.save(PromotionType.FREE_DELIVERY, PromotionScope.ORDER, p -> {
            p.setName(tag + " inactive");
            p.setActive(false);
        });

        for (String status : List.of("ACTIVE", "SCHEDULED", "ENDED", "INACTIVE")) {
            mockMvc.perform(get(ADMIN).with(TestAuth.as(manager)).param("q", tag).param("status", status))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].name").value(tag + " " + status.toLowerCase()))
                    .andExpect(jsonPath("$.content[0].status").value(status));
        }
        mockMvc.perform(get(ADMIN).with(TestAuth.as(manager)).param("q", tag))
                .andExpect(jsonPath("$.totalElements").value(4));

        // time moves: the scheduled one is running two days later
        clock.advance(Duration.ofDays(2));
        mockMvc.perform(get(ADMIN).with(TestAuth.as(manager)).param("q", tag).param("status", "ACTIVE"))
                .andExpect(jsonPath("$.content[*].name",
                        containsInAnyOrder(tag + " active", tag + " scheduled")));
    }

    @Test
    void listPromotions_couponAndTypeFilters() throws Exception {
        String code = promotions.uniqueCode("FLT");
        Promotion coupon = promotions.coupon(code, "5", null, null, null);
        String tag = coupon.getName();

        mockMvc.perform(get(ADMIN).with(TestAuth.as(manager)).param("q", code.toLowerCase()).param("coupon", "true"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].couponCode").value(code));
        mockMvc.perform(get(ADMIN).with(TestAuth.as(manager)).param("q", tag).param("coupon", "false"))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get(ADMIN).with(TestAuth.as(manager)).param("q", tag).param("type", "PERCENTAGE"))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get(ADMIN).with(TestAuth.as(manager)).param("q", tag).param("type", "FIXED_AMOUNT"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    // ---- broadcast -----------------------------------------------------------------------------

    @Test
    void broadcast_notifiesActiveCustomersOnce() throws Exception {
        User customer = system.user(Role.CUSTOMER);
        User inactiveCustomer = system.user(Role.CUSTOMER, false);
        User staff = system.user(Role.STAFF);
        String code = promotions.uniqueCode("BC");
        Promotion promotion = promotions.coupon(code, "5", "20", null, 1);

        mockMvc.perform(post(ADMIN + "/" + promotion.getPublicId() + "/broadcast").with(TestAuth.as(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.broadcastAt").isNotEmpty());

        List<Notification> received = promotionNotes(customer, promotion);
        assertThat(received).hasSize(1);
        assertThat(received.getFirst().getMessage()).contains("$5.00 off", "$20.00", code);
        assertThat(received.getFirst().getLink()).isEqualTo("/promotions");
        assertThat(promotionNotes(inactiveCustomer, promotion)).isEmpty();
        assertThat(promotionNotes(staff, promotion)).isEmpty();
        assertThat(promotionNotes(manager, promotion)).isEmpty();

        mockMvc.perform(post(ADMIN + "/" + promotion.getPublicId() + "/broadcast").with(TestAuth.as(manager)))
                .andExpect(status().isConflict());
        assertThat(promotionNotes(customer, promotion)).hasSize(1);
        assertThat(promotions.reload(promotion).getBroadcastAt()).isNotNull();
    }

    @Test
    void broadcast_notRunning_422() throws Exception {
        User customer = system.user(Role.CUSTOMER);
        Promotion scheduled = promotions.save(PromotionType.FREE_DELIVERY, PromotionScope.ORDER,
                p -> p.setStartDate(clock.instant().plus(Duration.ofDays(1))));
        Promotion inactive = promotions.save(PromotionType.FREE_DELIVERY, PromotionScope.ORDER,
                p -> p.setActive(false));

        for (Promotion promotion : List.of(scheduled, inactive)) {
            mockMvc.perform(post(ADMIN + "/" + promotion.getPublicId() + "/broadcast").with(TestAuth.as(manager)))
                    .andExpect(status().isUnprocessableEntity());
            assertThat(promotionNotes(customer, promotion)).isEmpty();
            assertThat(promotions.reload(promotion).getBroadcastAt()).isNull();
        }
    }

    // ---- roles ---------------------------------------------------------------------------------

    @Test
    void promotions_staff_403() throws Exception {
        Promotion promotion = promotions.percentageOnAll("5");
        var staff = TestAuth.asRole(1L, "STAFF");
        mockMvc.perform(get(ADMIN).with(staff)).andExpect(status().isForbidden());
        mockMvc.perform(post(ADMIN).with(staff).contentType(APPLICATION_JSON)
                        .content(json(body("FREE_DELIVERY", "ORDER", b -> { }))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(ADMIN + "/" + promotion.getPublicId() + "/broadcast").with(staff))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(ADMIN + "/" + promotion.getPublicId()).with(staff))
                .andExpect(status().isForbidden());
        assertThat(promotions.reload(promotion).isActive()).isTrue();
    }

    @Test
    void promotions_customer_403() throws Exception {
        Promotion promotion = promotions.percentageOnAll("5");
        var customer = TestAuth.asRole(1L, "CUSTOMER");
        mockMvc.perform(get(ADMIN).with(customer)).andExpect(status().isForbidden());
        mockMvc.perform(get(ADMIN + "/" + promotion.getPublicId()).with(customer))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(ADMIN + "/" + promotion.getPublicId()).with(customer).contentType(APPLICATION_JSON)
                        .content(json(body("FREE_DELIVERY", "ORDER", b -> { }))))
                .andExpect(status().isForbidden());
    }

    @Test
    void promotions_admin_allowed() throws Exception {
        mockMvc.perform(get(ADMIN).with(TestAuth.as(system.user(Role.ADMIN)))).andExpect(status().isOk());
    }

    // ---- helpers -------------------------------------------------------------------------------

    private List<Notification> promotionNotes(User user, Promotion promotion) {
        return system.notifications(user, NotificationType.PROMOTION).stream()
                .filter(n -> n.getTitle().equals(promotion.getName()))
                .toList();
    }

    private ResultActions create(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post(ADMIN).with(TestAuth.as(manager))
                .contentType(APPLICATION_JSON).content(json(body)));
    }

    private ResultActions update(Promotion promotion, Map<String, Object> body) throws Exception {
        return mockMvc.perform(put(ADMIN + "/" + promotion.getPublicId()).with(TestAuth.as(manager))
                .contentType(APPLICATION_JSON).content(json(body)));
    }

    private void expect422(Map<String, Object> body, String field) throws Exception {
        expect422(create(body), field);
    }

    private static void expect422(ResultActions result, String field) throws Exception {
        result.andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"))
                .andExpect(jsonPath("$.details." + field).exists());
    }

    private JsonNode read(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private Body body(String type, String applyTo, Consumer<Map<String, Object>> customize) {
        Body body = new Body();
        body.put("name", "Promo " + shortId());
        body.put("type", type);
        body.put("applyTo", applyTo);
        body.put("startDate", clock.instant().minus(Duration.ofHours(1)).toString());
        body.put("endDate", clock.instant().plus(Duration.ofDays(7)).toString());
        customize.accept(body);
        return body;
    }

    private Body couponBody(String code, String amount, Integer usageLimit) {
        return body("FIXED_AMOUNT", "ORDER", b -> {
            b.put("discountValue", amount);
            b.put("couponCode", code);
            b.put("usageLimit", usageLimit);
        });
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** A JSON body that keeps null values. */
    private static final class Body extends LinkedHashMap<String, Object> {
        Body andName(String name) {
            put("name", name);
            return this;
        }
    }
}
