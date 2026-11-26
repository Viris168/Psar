package com.psar.user.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.psar.delivery.entity.DeliveryCompany;
import com.psar.delivery.repository.DeliveryCompanyRepository;
import com.psar.support.IntegrationTest;
import com.psar.support.TestAuth;
import com.psar.user.entity.Address;
import com.psar.user.entity.Role;
import com.psar.user.entity.User;
import com.psar.user.repository.AddressRepository;
import com.psar.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AddressControllerTest extends IntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AddressRepository addressRepository;

    @Autowired
    private DeliveryCompanyRepository deliveryCompanyRepository;

    // ---- ownership / roles ---------------------------------------------------

    @Test
    void getAddress_otherUsersAddress_returns404() throws Exception {
        User owner = saveCustomer();
        User other = saveCustomer();
        UUID id = createOk(owner, cityAddress("Home", false));

        mockMvc.perform(get("/api/addresses/{id}", id).with(TestAuth.as(other)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/addresses/{id}", id).with(TestAuth.as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Home"));
    }

    @Test
    void updateDeleteSetDefault_otherUsersAddress_returns404AndLeavesAddressUnchanged() throws Exception {
        User owner = saveCustomer();
        User other = saveCustomer();
        UUID id = createOk(owner, cityAddress("Home", false));

        mockMvc.perform(put("/api/addresses/{id}", id).with(TestAuth.as(other))
                        .contentType(MediaType.APPLICATION_JSON).content(json(cityAddress("Hacked", false))))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/addresses/{id}/default", id).with(TestAuth.as(other)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/addresses/{id}", id).with(TestAuth.as(other)))
                .andExpect(status().isNotFound());

        Address address = addressRepository.findByPublicIdAndUserId(id, owner.getId()).orElseThrow();
        assertThat(address.getLabel()).isEqualTo("Home");
    }

    @Test
    void addresses_staffUser_returns403() throws Exception {
        User staff = saveUser(Role.STAFF);

        mockMvc.perform(get("/api/addresses").with(TestAuth.as(staff)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(post("/api/addresses").with(TestAuth.as(staff))
                        .contentType(MediaType.APPLICATION_JSON).content(json(cityAddress("Home", false))))
                .andExpect(status().isForbidden());
    }

    @Test
    void addresses_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/addresses"))
                .andExpect(status().isUnauthorized());
    }

    // ---- default address -----------------------------------------------------

    @Test
    void createAddress_firstAddress_becomesDefault() throws Exception {
        User user = saveCustomer();

        create(user, cityAddress("Home", false))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isDefault").value(true))
                .andExpect(jsonPath("$.publicId").exists())
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.id").doesNotExist());

        create(user, cityAddress("Work", false))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isDefault").value(false));
    }

    @Test
    void createAddress_isDefaultTrue_movesDefaultToNewAddress() throws Exception {
        User user = saveCustomer();
        UUID first = createOk(user, cityAddress("Home", false));
        UUID second = createOk(user, cityAddress("Work", true));

        assertThat(defaultOf(user)).isEqualTo(second);
        assertThat(isDefault(user, first)).isFalse();
    }

    @Test
    void setDefault_newAddress_unsetsPreviousDefault() throws Exception {
        User user = saveCustomer();
        UUID first = createOk(user, cityAddress("Home", false));
        UUID second = createOk(user, cityAddress("Work", false));

        mockMvc.perform(put("/api/addresses/{id}/default", second).with(TestAuth.as(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(second.toString()))
                .andExpect(jsonPath("$.isDefault").value(true));

        assertThat(defaultOf(user)).isEqualTo(second);
        assertThat(isDefault(user, first)).isFalse();

        // idempotent
        mockMvc.perform(put("/api/addresses/{id}/default", second).with(TestAuth.as(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(true));
    }

    @Test
    void updateAddress_isDefaultTrue_movesDefault() throws Exception {
        User user = saveCustomer();
        UUID first = createOk(user, cityAddress("Home", false));
        UUID second = createOk(user, cityAddress("Work", false));

        mockMvc.perform(put("/api/addresses/{id}", second).with(TestAuth.as(user))
                        .contentType(MediaType.APPLICATION_JSON).content(json(cityAddress("Office", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Office"))
                .andExpect(jsonPath("$.isDefault").value(true));

        assertThat(defaultOf(user)).isEqualTo(second);
        assertThat(isDefault(user, first)).isFalse();
    }

    @Test
    void updateAddress_defaultWithIsDefaultFalse_staysDefault() throws Exception {
        User user = saveCustomer();
        UUID first = createOk(user, cityAddress("Home", false));
        createOk(user, cityAddress("Work", false));

        mockMvc.perform(put("/api/addresses/{id}", first).with(TestAuth.as(user))
                        .contentType(MediaType.APPLICATION_JSON).content(json(cityAddress("Home 2", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(true));

        assertThat(defaultOf(user)).isEqualTo(first);
    }

    @Test
    void deleteAddress_default_promotesNewestRemaining() throws Exception {
        User user = saveCustomer();
        UUID first = createOk(user, cityAddress("Home", false));
        createOk(user, cityAddress("Work", false));
        UUID newest = createOk(user, cityAddress("Gym", false));

        mockMvc.perform(delete("/api/addresses/{id}", first).with(TestAuth.as(user)))
                .andExpect(status().isNoContent());

        assertThat(addressRepository.findByPublicIdAndUserId(first, user.getId())).isEmpty();
        assertThat(defaultOf(user)).isEqualTo(newest);
        assertThat(addressRepository.countByUserId(user.getId())).isEqualTo(2);
    }

    @Test
    void deleteAddress_lastAddress_leavesNoAddresses() throws Exception {
        User user = saveCustomer();
        UUID only = createOk(user, cityAddress("Home", false));

        mockMvc.perform(delete("/api/addresses/{id}", only).with(TestAuth.as(user)))
                .andExpect(status().isNoContent());

        assertThat(addressRepository.countByUserId(user.getId())).isZero();
    }

    // ---- validation / business rules -----------------------------------------

    @Test
    void createAddress_cityPinOutsidePhnomPenh_returns400() throws Exception {
        User user = saveCustomer();
        Map<String, Object> body = cityAddress("Siem Reap", false);
        body.put("latitude", "13.361");   // Siem Reap: inside Cambodia, outside Phnom Penh
        body.put("longitude", "103.860");

        create(user, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.details.latitude").exists())
                .andExpect(jsonPath("$.details.longitude").exists());

        assertThat(addressRepository.countByUserId(user.getId())).isZero();
    }

    @Test
    void createAddress_cityWithoutPin_returns400() throws Exception {
        User user = saveCustomer();
        Map<String, Object> body = cityAddress("Home", false);
        body.remove("latitude");
        body.remove("longitude");

        create(user, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void createAddress_activeDeliveryCompany_returns201() throws Exception {
        User user = saveCustomer();
        DeliveryCompany company = saveCompany(true);

        create(user, provinceAddress(company.getId()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deliveryType").value("PROVINCE"))
                .andExpect(jsonPath("$.deliveryCompanyId").value(company.getId()))
                .andExpect(jsonPath("$.latitude").doesNotExist());
    }

    @Test
    void createAddress_inactiveDeliveryCompany_returns422() throws Exception {
        User user = saveCustomer();
        DeliveryCompany company = saveCompany(false);

        create(user, provinceAddress(company.getId()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));

        assertThat(addressRepository.countByUserId(user.getId())).isZero();
    }

    @Test
    void createAddress_moreThanTen_returns422() throws Exception {
        User user = saveCustomer();
        for (int i = 0; i < 10; i++) {
            createOk(user, cityAddress("A" + i, false));
        }

        create(user, cityAddress("Eleventh", false))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));

        assertThat(addressRepository.countByUserId(user.getId())).isEqualTo(10);
    }

    // ---- list ----------------------------------------------------------------

    @Test
    void listAddresses_multiple_returnsDefaultFirstThenNewestOnlyOwn() throws Exception {
        User user = saveCustomer();
        User other = saveCustomer();
        createOk(other, cityAddress("Not mine", false));
        UUID first = createOk(user, cityAddress("Home", false));
        UUID second = createOk(user, cityAddress("Work", false));
        UUID third = createOk(user, cityAddress("Gym", false));

        String body = mockMvc.perform(get("/api/addresses").with(TestAuth.as(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andReturn().getResponse().getContentAsString();

        List<String> ids = objectMapper.readTree(body).get("content").findValuesAsText("publicId");
        assertThat(ids).containsExactly(first.toString(), third.toString(), second.toString());
    }

    @Test
    void listAddresses_pageSizeOver100_cappedAt100() throws Exception {
        User user = saveCustomer();

        mockMvc.perform(get("/api/addresses").param("size", "500").with(TestAuth.as(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    // ---- helpers -------------------------------------------------------------

    private ResultActions create(User user, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/addresses").with(TestAuth.as(user))
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private UUID createOk(User user, Map<String, Object> body) throws Exception {
        String response = create(user, body)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(response);
        return UUID.fromString(node.get("publicId").asText());
    }

    private UUID defaultOf(User user) {
        return addressRepository.findByUserIdAndDefaultAddressTrue(user.getId()).orElseThrow().getPublicId();
    }

    private boolean isDefault(User user, UUID publicId) {
        return addressRepository.findByPublicIdAndUserId(publicId, user.getId()).orElseThrow().isDefaultAddress();
    }

    private static Map<String, Object> cityAddress(String label, boolean isDefault) {
        Map<String, Object> body = new HashMap<>();
        body.put("label", label);
        body.put("receiverName", "Sok Dara");
        body.put("phone", "012345678");
        body.put("deliveryType", "CITY");
        body.put("province", "Phnom Penh");
        body.put("latitude", "11.556400");   // central Phnom Penh
        body.put("longitude", "104.928200");
        body.put("district", "Daun Penh");
        body.put("street", "St. 13");
        body.put("isDefault", isDefault);
        return body;
    }

    private static Map<String, Object> provinceAddress(Long deliveryCompanyId) {
        Map<String, Object> body = new HashMap<>();
        body.put("label", "Parents");
        body.put("receiverName", "Sok Dara");
        body.put("phone", "012345678");
        body.put("deliveryType", "PROVINCE");
        body.put("province", "Siem Reap");
        body.put("deliveryCompanyId", deliveryCompanyId);
        body.put("courierBranch", "Siem Reap central branch");
        body.put("isDefault", false);
        return body;
    }

    private User saveCustomer() {
        return saveUser(Role.CUSTOMER);
    }

    private User saveUser(Role role) {
        User user = new User();
        user.setEmail("address-" + UUID.randomUUID() + "@test.local");
        user.setPasswordHash("not-a-real-hash");
        user.setRole(role);
        return userRepository.save(user);
    }

    private DeliveryCompany saveCompany(boolean active) {
        DeliveryCompany company = new DeliveryCompany();
        company.setName("Courier " + UUID.randomUUID().toString().substring(0, 8));
        company.setActive(active);
        return deliveryCompanyRepository.save(company);
    }
}
