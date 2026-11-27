package com.psar.inventory.controller;

import com.psar.catalog.entity.Category;
import com.psar.catalog.entity.Product;
import com.psar.inventory.entity.MovementReason;
import com.psar.inventory.entity.MovementType;
import com.psar.inventory.entity.PurchaseOrder;
import com.psar.inventory.entity.StockMovement;
import com.psar.inventory.entity.Supplier;
import com.psar.inventory.repository.PurchaseOrderRepository;
import com.psar.inventory.repository.StockMovementRepository;
import com.psar.inventory.repository.SupplierRepository;
import com.psar.support.CatalogFixtures;
import com.psar.support.IntegrationTest;
import com.psar.support.TestAuth;
import com.psar.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminPurchaseOrderControllerTest extends IntegrationTest {

    private static final String URL = "/api/admin/purchase-orders";

    @Autowired
    private CatalogFixtures fixtures;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    private User manager;
    private Supplier supplier;
    private Product rice;
    private Product oil;

    @BeforeEach
    void setUp() {
        manager = fixtures.manager();
        supplier = fixtures.supplier("PO supplier");
        Category category = fixtures.category("PO api tests");
        rice = fixtures.product(category, "Rice 25kg", "30.00", 5, 10);
        oil = fixtures.product(category, "Palm oil", "3.00", 0, 10);
    }

    @Test
    void createPurchaseOrder_clientSendsTotal_ignoredAndRecalculated() throws Exception {
        Map<String, Object> body = Map.of(
                "supplierPublicId", supplier.getPublicId(),
                "totalAmount", "0.01",
                "items", List.of(
                        Map.of("productPublicId", rice.getPublicId(), "quantity", 3, "unitCost", "21.35",
                                "lineTotal", "1.00"),
                        Map.of("productPublicId", oil.getPublicId(), "quantity", 7, "unitCost", "1.15")));

        mockMvc.perform(post(URL).with(TestAuth.as(manager))
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.poNo").value(org.hamcrest.Matchers.matchesPattern("PO-\\d{6}")))
                .andExpect(jsonPath("$.status").value("PENDING"))
                // 3 × 21.35 + 7 × 1.15 = 64.05 + 8.05
                .andExpect(jsonPath("$.totalAmount").value(72.10))
                .andExpect(jsonPath("$.supplier.publicId").value(supplier.getPublicId().toString()))
                .andExpect(jsonPath("$.items[0].product.name").value("Rice 25kg"))
                .andExpect(jsonPath("$.items[0].lineTotal").value(64.05))
                .andExpect(jsonPath("$.items[0].product.id").doesNotExist());

        // creating a PO doesn't touch stock
        assertThat(fixtures.quantityOf(rice)).isEqualTo(5);
    }

    @Test
    void createPurchaseOrder_sameProductTwice_returns400() throws Exception {
        Map<String, Object> body = Map.of(
                "supplierPublicId", supplier.getPublicId(),
                "items", List.of(
                        Map.of("productPublicId", rice.getPublicId(), "quantity", 1, "unitCost", "1.00"),
                        Map.of("productPublicId", rice.getPublicId(), "quantity", 2, "unitCost", "1.00")));

        mockMvc.perform(post(URL).with(TestAuth.as(manager))
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createPurchaseOrder_invalidItems_returns400() throws Exception {
        Map<String, Object> noItems = Map.of("supplierPublicId", supplier.getPublicId(), "items", List.of());
        Map<String, Object> zeroQuantity = Map.of("supplierPublicId", supplier.getPublicId(),
                "items", List.of(Map.of("productPublicId", rice.getPublicId(), "quantity", 0, "unitCost", "1.00")));
        Map<String, Object> negativeCost = Map.of("supplierPublicId", supplier.getPublicId(),
                "items", List.of(Map.of("productPublicId", rice.getPublicId(), "quantity", 1, "unitCost", "-1")));

        for (Map<String, Object> body : List.of(noItems, zeroQuantity, negativeCost)) {
            mockMvc.perform(post(URL).with(TestAuth.as(manager))
                            .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void createPurchaseOrder_inactiveSupplier_returns422() throws Exception {
        supplier.setActive(false);
        supplierRepository.save(supplier);

        mockMvc.perform(post(URL).with(TestAuth.as(manager))
                        .contentType(MediaType.APPLICATION_JSON).content(json(oneItem(oil, 1))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createPurchaseOrder_unknownProduct_returns404() throws Exception {
        Map<String, Object> body = Map.of("supplierPublicId", supplier.getPublicId(),
                "items", List.of(Map.of("productPublicId", java.util.UUID.randomUUID(), "quantity", 1,
                        "unitCost", "1.00")));

        mockMvc.perform(post(URL).with(TestAuth.as(manager))
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isNotFound());
    }

    @Test
    void receivePurchaseOrder_pending_addsStockAndWritesMovements() throws Exception {
        String poNo = create(Map.of(
                "supplierPublicId", supplier.getPublicId(),
                "items", List.of(
                        Map.of("productPublicId", rice.getPublicId(), "quantity", 20, "unitCost", "25.00"),
                        Map.of("productPublicId", oil.getPublicId(), "quantity", 12, "unitCost", "2.10"))));

        mockMvc.perform(post(URL + "/" + poNo + "/receive").with(TestAuth.as(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.receivedAt").isNotEmpty());

        assertThat(fixtures.quantityOf(rice)).isEqualTo(25);
        assertThat(fixtures.quantityOf(oil)).isEqualTo(12);
        PurchaseOrder po = purchaseOrderRepository.findByPoNo(poNo).orElseThrow();
        List<StockMovement> movements = stockMovementRepository.findAll((root, query, cb) ->
                cb.and(cb.equal(root.get("reason"), MovementReason.PURCHASE),
                        cb.equal(root.get("referenceId"), po.getId())));
        assertThat(movements).hasSize(2).allSatisfy(m -> {
            assertThat(m.getType()).isEqualTo(MovementType.IN);
            assertThat(m.getReason()).isEqualTo(MovementReason.PURCHASE);
            assertThat(m.getCreatedBy()).isEqualTo(manager.getId());
        });
        assertThat(movements).filteredOn(m -> m.getProductId().equals(rice.getId()))
                .singleElement().satisfies(m -> {
                    assertThat(m.getQuantity()).isEqualTo(20);
                    assertThat(m.getBalanceAfter()).isEqualTo(25);
                });
    }

    @Test
    void receivePurchaseOrder_cancelled_returns409() throws Exception {
        String poNo = create(oneItem(oil, 4));

        mockMvc.perform(post(URL + "/" + poNo + "/cancel").with(TestAuth.as(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty());

        mockMvc.perform(post(URL + "/" + poNo + "/receive").with(TestAuth.as(manager)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        mockMvc.perform(post(URL + "/" + poNo + "/cancel").with(TestAuth.as(manager)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        assertThat(fixtures.quantityOf(oil)).isZero();
    }

    @Test
    void receivePurchaseOrder_unknown_returns404() throws Exception {
        mockMvc.perform(post(URL + "/PO-999999999/receive").with(TestAuth.as(manager)))
                .andExpect(status().isNotFound());
    }

    @Test
    void listPurchaseOrders_filteredBySupplierAndStatus_returnsMatching() throws Exception {
        String pending = create(oneItem(oil, 1));
        String cancelled = create(oneItem(oil, 2));
        mockMvc.perform(post(URL + "/" + cancelled + "/cancel").with(TestAuth.as(manager)))
                .andExpect(status().isOk());

        mockMvc.perform(get(URL).with(TestAuth.as(manager))
                        .param("supplier", supplier.getPublicId().toString())
                        .param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].poNo").value(pending))
                .andExpect(jsonPath("$.content[0].supplier.name").value(supplier.getName()));

        mockMvc.perform(get(URL + "/" + cancelled).with(TestAuth.as(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].product.publicId").value(oil.getPublicId().toString()));
    }

    @Test
    void purchaseOrders_staff_returns403() throws Exception {
        mockMvc.perform(get(URL).with(TestAuth.asRole(1L, "STAFF")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(URL).with(TestAuth.asRole(1L, "STAFF"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(oneItem(oil, 1))))
                .andExpect(status().isForbidden());
    }

    private Map<String, Object> oneItem(Product product, int quantity) {
        return Map.of("supplierPublicId", supplier.getPublicId(),
                "items", List.of(Map.of("productPublicId", product.getPublicId(), "quantity", quantity,
                        "unitCost", new BigDecimal("1.00"))));
    }

    private String create(Map<String, Object> body) throws Exception {
        String response = mockMvc.perform(post(URL).with(TestAuth.as(manager))
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("poNo").asText();
    }
}
