package com.psar.audit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.psar.audit.entity.AuditAction;
import com.psar.audit.entity.AuditLog;
import com.psar.audit.repository.AuditLogRepository;
import com.psar.catalog.dto.request.ProductRequest;
import com.psar.catalog.entity.Category;
import com.psar.catalog.entity.Product;
import com.psar.catalog.service.ProductService;
import com.psar.common.security.AuthenticatedUser;
import com.psar.inventory.entity.MovementReason;
import com.psar.inventory.entity.Supplier;
import com.psar.inventory.repository.StockMovementRepository;
import com.psar.support.CatalogFixtures;
import com.psar.support.IntegrationTest;
import com.psar.support.SystemFixtures;
import com.psar.support.TestAuth;
import com.psar.user.entity.Role;
import com.psar.user.entity.User;
import com.psar.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The audit calls in the existing services: what is written, by whom, and that it rolls back with the change. */
class AuditTrailTest extends IntegrationTest {

    private static final String PASSWORD = "Password123!";
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    @Autowired
    private SystemFixtures system;
    @Autowired
    private CatalogFixtures fixtures;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private StockMovementRepository stockMovementRepository;
    @Autowired
    private ProductService productService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private User manager;
    private Category category;

    @BeforeEach
    void setUp() {
        manager = system.user(Role.MANAGER);
        category = fixtures.category("Audit trail");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- products ----------------------------------------------------------

    @Test
    void createProduct_writesCreateAuditRow() throws Exception {
        String sku = "AUD-" + shortId();
        JsonNode created = createProduct(productBody(sku, "Jasmine rice")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString().transform(this::read);
        String publicId = created.get("publicId").asText();

        assertThat(system.auditRows("PRODUCT", publicId)).singleElement().satisfies(row -> {
            assertThat(row.getAction()).isEqualTo(AuditAction.CREATE);
            assertThat(row.getUserId()).isEqualTo(manager.getId());
            assertThat(row.getIpAddress()).isNotBlank();
            assertThat(row.getDetails())
                    .containsEntry("sku", sku.toUpperCase())
                    .containsEntry("name", "Jasmine rice")
                    .containsEntry("categorySlug", category.getSlug())
                    .containsEntry("costPrice", "1.20")
                    .containsEntry("sellPrice", "1.75")
                    .doesNotContainKey("quantity");
        });
    }

    @Test
    void updateProduct_writesOnlyChangedFields() throws Exception {
        Product product = fixtures.product(category, "Fish sauce", "2.00", 0, 10);
        Map<String, Object> body = productBody(product.getSku(), "Fish sauce 700ml");
        body.put("costPrice", product.getCostPrice());
        body.put("sellPrice", "2.25");
        body.put("minStock", 10);

        updateProduct(product, body).andExpect(status().isOk());

        assertThat(system.auditRows("PRODUCT", product.getPublicId().toString())).singleElement().satisfies(row -> {
            assertThat(row.getAction()).isEqualTo(AuditAction.UPDATE);
            assertThat(row.getUserId()).isEqualTo(manager.getId());
            assertThat(row.getDetails()).isEqualTo(Map.of(
                    "name", Map.of("from", "Fish sauce", "to", "Fish sauce 700ml"),
                    "sellPrice", Map.of("from", "2.00", "to", "2.25")));
        });
    }

    @Test
    void updateProduct_nothingChanged_writesNoRow() throws Exception {
        Product product = fixtures.product(category, "Soy sauce", "1.00", 0, 10);
        Map<String, Object> body = productBody(product.getSku(), product.getName());
        body.put("costPrice", "0.50");
        body.put("sellPrice", "1.0"); // same money, other scale
        body.put("minStock", 10);
        body.put("unit", "pcs");

        updateProduct(product, body).andExpect(status().isOk());

        assertThat(system.auditRows("PRODUCT", product.getPublicId().toString())).isEmpty();
    }

    @Test
    void createProduct_failsValidation_writesNoAuditRow() throws Exception {
        Category inactive = fixtures.category("Audit inactive");
        mockMvc.perform(delete("/api/admin/categories/" + inactive.getSlug()).with(TestAuth.as(manager)))
                .andExpect(status().isNoContent());
        Map<String, Object> body = productBody("AUD-" + shortId(), "Never saved");
        body.put("categorySlug", inactive.getSlug());

        createProduct(body).andExpect(status().isUnprocessableEntity());
        assertThat(rowsBy(manager)).extracting(AuditLog::getEntity).containsOnly("CATEGORY");

        // a failure after the audit call rolls the row back with the product
        loginAs(manager);
        ProductRequest request = new ProductRequest("AUD-" + shortId(), "Rolled back", null, null,
                category.getSlug(), null, new BigDecimal("1.00"), new BigDecimal("2.00"), null, null, null);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            productService.create(request);
            status.setRollbackOnly();
        });
        assertThat(rowsBy(manager)).extracting(AuditLog::getEntity).containsOnly("CATEGORY");
    }

    @Test
    void deleteProduct_writesNameAndSku_secondDeleteWritesNothing() throws Exception {
        Product product = fixtures.product(category, "Palm sugar", "3.00", 0, 0);

        mockMvc.perform(delete("/api/admin/products/" + product.getPublicId()).with(TestAuth.as(manager)))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/admin/products/" + product.getPublicId()).with(TestAuth.as(manager)))
                .andExpect(status().isNoContent());

        assertThat(system.auditRows("PRODUCT", product.getPublicId().toString())).singleElement()
                .satisfies(row -> {
                    assertThat(row.getAction()).isEqualTo(AuditAction.DELETE);
                    assertThat(row.getDetails()).isEqualTo(Map.of("sku", product.getSku(), "name", "Palm sugar"));
                });
    }

    @Test
    void uploadAndDeleteImage_writeRowsWithoutStorageKey() throws Exception {
        Product product = fixtures.product(category, "Durian", "9.00", 0, 0);
        MockMultipartFile png = new MockMultipartFile("file", "d.png", "image/png", png());

        JsonNode image = mockMvc.perform(multipart("/api/admin/products/" + product.getPublicId() + "/images")
                        .file(png).with(TestAuth.as(manager)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString().transform(this::read);
        String imageId = image.get("publicId").asText();
        mockMvc.perform(delete("/api/admin/products/" + product.getPublicId() + "/images/" + imageId)
                        .with(TestAuth.as(manager)))
                .andExpect(status().isNoContent());

        List<AuditLog> rows = system.auditRows("PRODUCT_IMAGE", imageId);
        assertThat(rows).extracting(AuditLog::getAction).containsExactly(AuditAction.CREATE, AuditAction.DELETE);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getDetails()).containsEntry("productPublicId", product.getPublicId().toString())
                    .containsKey("imageUrl").doesNotContainKey("storageKey");
        });
    }

    // ---- auth --------------------------------------------------------------

    @Test
    void login_success_writesLoginRow() throws Exception {
        String email = register();
        User user = userRepository.findByEmail(email).orElseThrow();

        login(email, PASSWORD).andExpect(status().isOk());

        assertThat(system.auditRows(AuditService.AUTH, user.getPublicId().toString())).singleElement().satisfies(row -> {
            assertThat(row.getAction()).isEqualTo(AuditAction.LOGIN);
            assertThat(row.getUserId()).isEqualTo(user.getId());
            assertThat(row.getDetails()).isEqualTo(Map.of("email", email));
        });
    }

    @Test
    void login_wrongPassword_writesLoginFailedRowWithoutPassword() throws Exception {
        String email = register();
        String wrongPassword = "Wrong-" + shortId();

        login(email.toUpperCase(), wrongPassword).andExpect(status().isUnauthorized());
        login("nobody-" + email, wrongPassword).andExpect(status().isUnauthorized());

        List<AuditLog> failed = system.auditRows(AuditService.AUTH, null).stream()
                .filter(row -> row.getDetails().get("email").toString().endsWith(email))
                .toList();
        assertThat(failed).hasSize(2).allSatisfy(row -> {
            assertThat(row.getAction()).isEqualTo(AuditAction.LOGIN_FAILED);
            assertThat(row.getDetails()).containsOnlyKeys("email", "reason")
                    .containsEntry("reason", "BAD_CREDENTIALS");
            assertThat(row.getDetails().toString()).doesNotContain(wrongPassword);
        });
        assertThat(failed).filteredOn(row -> email.equals(row.getDetails().get("email")))
                .singleElement().satisfies(row -> assertThat(row.getUserId()).isNotNull());
        assertThat(failed).filteredOn(row -> !email.equals(row.getDetails().get("email")))
                .singleElement().satisfies(row -> assertThat(row.getUserId()).isNull());
    }

    // ---- users -------------------------------------------------------------

    @Test
    void changeRole_writesFromAndTo() throws Exception {
        User admin = system.user(Role.ADMIN);
        User staff = system.user(Role.STAFF);

        changeRole(admin, staff, "MANAGER");
        changeRole(admin, staff, "MANAGER"); // unchanged: no row

        assertThat(system.auditRows("USER", staff.getPublicId().toString())).singleElement().satisfies(row -> {
            assertThat(row.getAction()).isEqualTo(AuditAction.UPDATE);
            assertThat(row.getUserId()).isEqualTo(admin.getId());
            assertThat(row.getDetails()).isEqualTo(Map.of("role", Map.of("from", "STAFF", "to", "MANAGER")));
        });
    }

    @Test
    void createStaffAndDisable_writeCreateAndStatusRowsWithoutPassword() throws Exception {
        User admin = system.user(Role.ADMIN);
        String email = "audit-staff-" + shortId() + "@test.local";
        JsonNode created = mockMvc.perform(post("/api/admin/users").with(TestAuth.as(admin))
                        .contentType(APPLICATION_JSON).content(json(Map.of("email", email, "password", PASSWORD,
                                "fullName", "Sok Dara", "role", "STAFF"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString().transform(this::read);
        String publicId = created.get("publicId").asText();
        mockMvc.perform(patch("/api/admin/users/" + publicId + "/status").with(TestAuth.as(admin))
                        .contentType(APPLICATION_JSON).content(json(Map.of("active", false))))
                .andExpect(status().isOk());

        List<AuditLog> rows = system.auditRows("USER", publicId);
        assertThat(rows).extracting(AuditLog::getAction).containsExactly(AuditAction.CREATE, AuditAction.UPDATE);
        assertThat(rows.get(0).getDetails()).containsEntry("email", email).containsEntry("role", "STAFF")
                .doesNotContainKeys("password", "passwordHash");
        assertThat(rows.get(0).getDetails().toString()).doesNotContain(PASSWORD);
        assertThat(rows.get(1).getDetails()).isEqualTo(Map.of("active", Map.of("from", true, "to", false)));
    }

    // ---- inventory ---------------------------------------------------------

    @Test
    void adjustStock_writesAuditRowAndMovement() throws Exception {
        Product product = fixtures.product(category, "Eggs 30", "4.00", 10, 0);

        mockMvc.perform(post("/api/admin/inventory/adjustments").with(TestAuth.as(manager))
                        .contentType(APPLICATION_JSON).content(json(Map.of("productPublicId", product.getPublicId(),
                                "change", -3, "reason", "DAMAGE", "note", "Dropped a tray"))))
                .andExpect(status().isCreated());

        assertThat(stockMovementRepository.count((root, query, cb) ->
                cb.equal(root.get("productId"), product.getId()))).isEqualTo(1);
        assertThat(system.auditRows("STOCK_ADJUSTMENT", product.getPublicId().toString())).singleElement()
                .satisfies(row -> {
                    assertThat(row.getAction()).isEqualTo(AuditAction.CREATE);
                    assertThat(row.getUserId()).isEqualTo(manager.getId());
                    assertThat(row.getDetails()).containsEntry("change", -3).containsEntry("reason", "DAMAGE")
                            .containsEntry("balanceAfter", 7).containsEntry("note", "Dropped a tray")
                            .containsEntry("sku", product.getSku());
                });
    }

    @Test
    void adjustStock_notEnoughStock_writesNoAuditRow() throws Exception {
        Product product = fixtures.product(category, "Milk", "1.50", 2, 0);

        mockMvc.perform(post("/api/admin/inventory/adjustments").with(TestAuth.as(manager))
                        .contentType(APPLICATION_JSON).content(json(Map.of("productPublicId", product.getPublicId(),
                                "change", -5, "reason", "DAMAGE"))))
                .andExpect(status().isConflict());

        assertThat(system.auditRows("STOCK_ADJUSTMENT", product.getPublicId().toString())).isEmpty();
    }

    @Test
    void purchaseOrder_createAndReceive_writeCreateAndStatusRows_noStockAdjustmentRow() throws Exception {
        Supplier supplier = fixtures.supplier("Audit supplier " + shortId());
        Product product = fixtures.product(category, "Rice 25kg", "30.00", 0, 0);
        JsonNode po = mockMvc.perform(post("/api/admin/purchase-orders").with(TestAuth.as(manager))
                        .contentType(APPLICATION_JSON).content(json(Map.of("supplierPublicId", supplier.getPublicId(),
                                "items", List.of(Map.of("productPublicId", product.getPublicId(), "quantity", 3,
                                        "unitCost", "21.35"))))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString().transform(this::read);
        String poNo = po.get("poNo").asText();
        mockMvc.perform(post("/api/admin/purchase-orders/" + poNo + "/receive").with(TestAuth.as(manager)))
                .andExpect(status().isOk());

        List<AuditLog> rows = system.auditRows("PURCHASE_ORDER", poNo);
        assertThat(rows).extracting(AuditLog::getAction).containsExactly(AuditAction.CREATE, AuditAction.UPDATE);
        assertThat(rows.get(0).getDetails()).containsEntry("totalAmount", "64.05")
                .containsEntry("supplierPublicId", supplier.getPublicId().toString());
        assertThat(rows.get(1).getDetails()).isEqualTo(Map.of("status", Map.of("from", "PENDING", "to", "RECEIVED")));
        assertThat(system.auditRows("STOCK_ADJUSTMENT", product.getPublicId().toString())).isEmpty();
    }

    @Test
    void supplierAndCategoryAndDeliveryCompany_crud_writeRows() throws Exception {
        JsonNode supplier = mockMvc.perform(post("/api/admin/suppliers").with(TestAuth.as(manager))
                        .contentType(APPLICATION_JSON).content(json(Map.of("name", "Kampot Pepper " + shortId()))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString().transform(this::read);
        String supplierId = supplier.get("publicId").asText();
        mockMvc.perform(put("/api/admin/suppliers/" + supplierId).with(TestAuth.as(manager))
                        .contentType(APPLICATION_JSON).content(json(Map.of("name", supplier.get("name").asText(),
                                "phone", "012345678"))))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/admin/suppliers/" + supplierId).with(TestAuth.as(manager)))
                .andExpect(status().isNoContent());
        assertThat(system.auditRows("SUPPLIER", supplierId)).extracting(AuditLog::getAction)
                .containsExactly(AuditAction.CREATE, AuditAction.UPDATE, AuditAction.DELETE);
        assertThat(system.auditRows("SUPPLIER", supplierId).get(1).getDetails())
                .isEqualTo(Map.of("phone", mapOf("from", null, "to", "012345678")));

        JsonNode created = mockMvc.perform(post("/api/admin/categories").with(TestAuth.as(manager))
                        .contentType(APPLICATION_JSON).content(json(Map.of("name", "Spices " + shortId()))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString().transform(this::read);
        String slug = created.get("slug").asText();
        mockMvc.perform(delete("/api/admin/categories/" + slug).with(TestAuth.as(manager)))
                .andExpect(status().isNoContent());
        assertThat(system.auditRows("CATEGORY", slug)).extracting(AuditLog::getAction)
                .containsExactly(AuditAction.CREATE, AuditAction.DELETE);

        User admin = system.user(Role.ADMIN);
        JsonNode company = mockMvc.perform(post("/api/admin/delivery-companies").with(TestAuth.as(admin))
                        .contentType(APPLICATION_JSON).content(json(Map.of("name", "J&T " + shortId(), "baseFee", "1.50"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString().transform(this::read);
        String companyId = company.get("id").asText();
        mockMvc.perform(put("/api/admin/delivery-companies/" + companyId).with(TestAuth.as(admin))
                        .contentType(APPLICATION_JSON).content(json(Map.of("name", company.get("name").asText(),
                                "baseFee", "2.0"))))
                .andExpect(status().isOk());
        assertThat(system.auditRows("DELIVERY_COMPANY", companyId)).extracting(AuditLog::getAction)
                .containsExactly(AuditAction.CREATE, AuditAction.UPDATE);
        assertThat(system.auditRows("DELIVERY_COMPANY", companyId).get(1).getDetails())
                .isEqualTo(Map.of("baseFee", Map.of("from", "1.50", "to", "2.00")));
    }

    // ---- helpers -----------------------------------------------------------

    private ResultActions createProduct(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/admin/products").with(TestAuth.as(manager))
                .contentType(APPLICATION_JSON).content(json(body)));
    }

    private ResultActions updateProduct(Product product, Map<String, Object> body) throws Exception {
        return mockMvc.perform(put("/api/admin/products/" + product.getPublicId()).with(TestAuth.as(manager))
                .contentType(APPLICATION_JSON).content(json(body)));
    }

    private Map<String, Object> productBody(String sku, String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sku", sku);
        body.put("name", name);
        body.put("categorySlug", category.getSlug());
        body.put("costPrice", "1.20");
        body.put("sellPrice", "1.75");
        return body;
    }

    private void changeRole(User admin, User target, String role) throws Exception {
        mockMvc.perform(patch("/api/admin/users/" + target.getPublicId() + "/role").with(TestAuth.as(admin))
                        .contentType(APPLICATION_JSON).content(json(Map.of("role", role))))
                .andExpect(status().isOk());
    }

    private String register() throws Exception {
        String email = "audit-" + shortId() + "@test.local";
        mockMvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", PASSWORD, "fullName", "Audit User"))))
                .andExpect(status().isCreated());
        return email;
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content(json(Map.of("email", email, "password", password))));
    }

    private List<AuditLog> rowsBy(User user) {
        return auditLogRepository.findAll((root, query, cb) -> cb.equal(root.get("userId"), user.getId()));
    }

    private static void loginAs(User user) {
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getPublicId(), user.getEmail(),
                user.getRole().name());
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    }

    private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }

    private static byte[] png() {
        byte[] data = new byte[128];
        System.arraycopy(PNG_MAGIC, 0, data, 0, PNG_MAGIC.length);
        return data;
    }

    private JsonNode read(String content) {
        try {
            return objectMapper.readTree(content);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
