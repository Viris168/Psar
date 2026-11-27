package com.psar.inventory.controller;

import com.psar.inventory.entity.PurchaseOrder;
import com.psar.inventory.entity.PurchaseOrderStatus;
import com.psar.inventory.entity.Supplier;
import com.psar.inventory.repository.PurchaseOrderRepository;
import com.psar.inventory.repository.SupplierRepository;
import com.psar.support.CatalogFixtures;
import com.psar.support.IntegrationTest;
import com.psar.support.TestAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminSupplierControllerTest extends IntegrationTest {

    private static final String URL = "/api/admin/suppliers";
    private static final RequestPostProcessor MANAGER = TestAuth.asRole(1L, "MANAGER");
    private static final RequestPostProcessor STAFF = TestAuth.asRole(1L, "STAFF");

    @Autowired
    private CatalogFixtures fixtures;
    @Autowired
    private SupplierRepository supplierRepository;
    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Test
    void createSupplier_valid_returns201WithPublicIdOnly() throws Exception {
        String name = "Angkor Dairy " + shortId();
        mockMvc.perform(post(URL).with(MANAGER).contentType(APPLICATION_JSON)
                        .content(json(body("  " + name + "  ", "sales@angkor.test"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.publicId").isNotEmpty())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void createSupplier_blankNameOrBadEmail_returns400() throws Exception {
        mockMvc.perform(post(URL).with(MANAGER).contentType(APPLICATION_JSON).content(json(body(" ", "not-an-email"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.name").exists())
                .andExpect(jsonPath("$.details.email").exists());
    }

    @Test
    void updateSupplier_changesFieldsAndCanRestore() throws Exception {
        Supplier supplier = fixtures.supplier("Old " + shortId());
        supplier.setActive(false);
        supplierRepository.save(supplier);

        Map<String, Object> body = body("New " + shortId(), null);
        body.put("active", true);
        mockMvc.perform(put(URL + "/{id}", supplier.getPublicId()).with(MANAGER)
                        .contentType(APPLICATION_JSON).content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(body.get("name")))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void getSupplier_unknown_returns404() throws Exception {
        mockMvc.perform(get(URL + "/{id}", UUID.randomUUID()).with(STAFF))
                .andExpect(status().isNotFound());
    }

    @Test
    void listSuppliers_qAndActiveFilter_returnsMatchesOnly() throws Exception {
        String tag = shortId();
        Supplier active = fixtures.supplier("Mekong " + tag);
        Supplier inactive = fixtures.supplier("Mekong " + tag + " closed");
        inactive.setActive(false);
        supplierRepository.save(inactive);
        fixtures.supplier("Unrelated " + shortId());

        mockMvc.perform(get(URL).with(STAFF).param("q", "MEKONG " + tag).param("active", "true").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].publicId").value(active.getPublicId().toString()));

        mockMvc.perform(get(URL).with(STAFF).param("q", tag).param("size", "100"))
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get(URL).with(STAFF).param("active", "false").param("size", "100"))
                .andExpect(jsonPath("$.content[*].active", everyItem(is(false))))
                .andExpect(jsonPath("$.content[*].name", hasItem(inactive.getName())))
                .andExpect(jsonPath("$.content[*].name", not(hasItem(active.getName()))));
    }

    @Test
    void deleteSupplier_withPendingPurchaseOrder_returns422() throws Exception {
        Supplier supplier = fixtures.supplier("Busy " + shortId());
        purchaseOrder(supplier, PurchaseOrderStatus.PENDING);

        mockMvc.perform(delete(URL + "/{id}", supplier.getPublicId()).with(MANAGER))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));
        assertThat(supplierRepository.findById(supplier.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void deleteSupplier_onlyFinishedPurchaseOrders_softDeletes() throws Exception {
        Supplier supplier = fixtures.supplier("Done " + shortId());
        purchaseOrder(supplier, PurchaseOrderStatus.RECEIVED);
        purchaseOrder(supplier, PurchaseOrderStatus.CANCELLED);

        mockMvc.perform(delete(URL + "/{id}", supplier.getPublicId()).with(MANAGER))
                .andExpect(status().isNoContent());
        assertThat(supplierRepository.findById(supplier.getId()).orElseThrow().isActive()).isFalse();
    }

    @Test
    void suppliers_staff_canReadButNotWrite() throws Exception {
        Supplier supplier = fixtures.supplier("Read only " + shortId());

        mockMvc.perform(get(URL).with(STAFF)).andExpect(status().isOk());
        mockMvc.perform(get(URL + "/{id}", supplier.getPublicId()).with(STAFF)).andExpect(status().isOk());
        mockMvc.perform(post(URL).with(STAFF).contentType(APPLICATION_JSON).content(json(body("X", null))))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(URL + "/{id}", supplier.getPublicId()).with(STAFF)
                        .contentType(APPLICATION_JSON).content(json(body("X", null))))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(URL + "/{id}", supplier.getPublicId()).with(STAFF))
                .andExpect(status().isForbidden());
        assertThat(supplierRepository.findById(supplier.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void suppliers_customer_returns403() throws Exception {
        mockMvc.perform(get(URL).with(TestAuth.asRole(1L, "CUSTOMER")))
                .andExpect(status().isForbidden());
    }

    private void purchaseOrder(Supplier supplier, PurchaseOrderStatus status) {
        PurchaseOrder po = new PurchaseOrder();
        po.setSupplier(supplier);
        po.setStatus(status);
        po.setCreatedBy(fixtures.manager().getId());
        purchaseOrderRepository.save(po);
    }

    private static Map<String, Object> body(String name, String email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("phone", "012 345 678");
        body.put("email", email);
        body.put("address", "Phnom Penh");
        return body;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
