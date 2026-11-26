package com.psar.delivery.controller;

import com.psar.delivery.entity.DeliveryCompany;
import com.psar.delivery.repository.DeliveryCompanyRepository;
import com.psar.support.IntegrationTest;
import com.psar.support.TestAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
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

/** Customer list (/api/delivery-companies) and admin CRUD (/api/admin/delivery-companies). */
class DeliveryCompanyControllerTest extends IntegrationTest {

    private static final String PUBLIC = "/api/delivery-companies";
    private static final String ADMIN = "/api/admin/delivery-companies";

    @Autowired
    private DeliveryCompanyRepository deliveryCompanyRepository;

    // ---- customer list -----------------------------------------------------

    @Test
    void listDeliveryCompanies_customer_returnsActiveOnly() throws Exception {
        String active = save("Active " + shortId(), true).getName();
        String inactive = save("Inactive " + shortId(), false).getName();

        mockMvc.perform(get(PUBLIC).with(TestAuth.asRole(1L, "CUSTOMER"))
                        .param("size", "100").param("sort", "createdAt,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].active", everyItem(is(true))))
                .andExpect(jsonPath("$.content[*].name", hasItem(active)))
                .andExpect(jsonPath("$.content[*].name", not(hasItem(inactive))));
    }

    @Test
    void listDeliveryCompanies_noAuth_returns401() throws Exception {
        mockMvc.perform(get(PUBLIC)).andExpect(status().isUnauthorized());
    }

    // ---- admin access ------------------------------------------------------

    @Test
    void adminDeliveryCompanies_managerRole_returns403() throws Exception {
        mockMvc.perform(get(ADMIN).with(TestAuth.asRole(1L, "MANAGER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void adminDeliveryCompanies_customerRole_returns403() throws Exception {
        mockMvc.perform(post(ADMIN).with(TestAuth.asRole(1L, "CUSTOMER"))
                        .contentType(APPLICATION_JSON).content(json(body("Nope", true))))
                .andExpect(status().isForbidden());
    }

    // ---- admin CRUD --------------------------------------------------------

    @Test
    void adminList_mixedCompanies_includesInactive() throws Exception {
        String inactive = save("Hidden " + shortId(), false).getName();

        mockMvc.perform(get(ADMIN).with(TestAuth.asRole(1L, "ADMIN"))
                        .param("size", "100").param("sort", "createdAt,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].name", hasItem(inactive)));
    }

    @Test
    void create_validRequest_returns201() throws Exception {
        String name = "Vireak Buntham " + shortId();

        mockMvc.perform(post(ADMIN).with(TestAuth.asRole(1L, "ADMIN"))
                        .contentType(APPLICATION_JSON).content(json(body(name, true))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.baseFee").value(1.5))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void create_invalidRequest_returns400() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "");
        body.put("baseFee", -1);

        mockMvc.perform(post(ADMIN).with(TestAuth.asRole(1L, "ADMIN"))
                        .contentType(APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.name").exists())
                .andExpect(jsonPath("$.details.baseFee").exists());
    }

    @Test
    void update_existingCompany_returnsUpdated() throws Exception {
        DeliveryCompany company = save("Before " + shortId(), true);
        String name = "After " + shortId();

        mockMvc.perform(put(ADMIN + "/" + company.getId()).with(TestAuth.asRole(1L, "ADMIN"))
                        .contentType(APPLICATION_JSON).content(json(body(name, true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(name));

        assertThat(deliveryCompanyRepository.findById(company.getId()).orElseThrow().getName()).isEqualTo(name);
    }

    @Test
    void update_unknownId_returns404() throws Exception {
        mockMvc.perform(put(ADMIN + "/" + Long.MAX_VALUE).with(TestAuth.asRole(1L, "ADMIN"))
                        .contentType(APPLICATION_JSON).content(json(body("Ghost", true))))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_existingCompany_returns204AndKeepsRowInactive() throws Exception {
        DeliveryCompany company = save("Doomed " + shortId(), true);

        mockMvc.perform(delete(ADMIN + "/" + company.getId()).with(TestAuth.asRole(1L, "ADMIN")))
                .andExpect(status().isNoContent());

        assertThat(deliveryCompanyRepository.findById(company.getId()))
                .hasValueSatisfying(c -> assertThat(c.isActive()).isFalse());
    }

    // ---- helpers -----------------------------------------------------------

    private DeliveryCompany save(String name, boolean active) {
        DeliveryCompany company = new DeliveryCompany();
        company.setName(name);
        company.setActive(active);
        return deliveryCompanyRepository.save(company);
    }

    private static Map<String, Object> body(String name, boolean active) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("phone", "012 345 678");
        body.put("trackingUrl", "https://courier.example/track/{trackingNo}");
        body.put("baseFee", new BigDecimal("1.50"));
        body.put("active", active);
        return body;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
