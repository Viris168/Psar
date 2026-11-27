package com.psar;

import com.fasterxml.jackson.databind.JsonNode;
import com.psar.support.IntegrationTest;
import com.psar.user.entity.Role;
import com.psar.user.entity.User;
import com.psar.user.entity.UserProfile;
import com.psar.user.repository.UserProfileRepository;
import com.psar.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 1 end to end, with real JWTs (no TestAuth): register -> login -> profile -> address
 * -> admin disables the customer -> the customer's existing tokens stop working.
 */
class AccountFlowEndToEndTest extends IntegrationTest {

    private static final String PASSWORD = "Password123!";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void accountFlow_adminDisablesCustomer_customerTokensStopWorking() throws Exception {
        String email = "e2e-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";

        // 1. customer registers, then logs in
        send(post("/api/auth/register"), null, Map.of("email", email, "password", PASSWORD, "fullName", "Sok Dara"))
                .andExpect(status().isCreated());
        JsonNode tokens = body(send(post("/api/auth/login"), null, Map.of("email", email, "password", PASSWORD))
                .andExpect(status().isOk()));
        String accessToken = tokens.get("accessToken").asText();
        String refreshToken = tokens.get("refreshToken").asText();

        // 2. profile and a CITY address, using the real access token
        String customerPublicId = body(send(get("/api/me"), accessToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.fullName").value("Sok Dara")))
                .get("publicId").asText();

        send(post("/api/addresses"), accessToken, cityAddress())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isDefault").value(true));
        send(get("/api/addresses"), accessToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        // a customer can't reach admin endpoints
        send(get("/api/admin/users"), accessToken, null).andExpect(status().isForbidden());

        // 3. an admin logs in and disables the customer
        String adminToken = loginAsNewAdmin();
        send(get("/api/admin/users").param("q", email), adminToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].publicId").value(customerPublicId))
                .andExpect(jsonPath("$.content[0].fullName").value("Sok Dara"));
        send(patch("/api/admin/users/" + customerPublicId + "/status"), adminToken, Map.of("active", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        // 4. the customer is locked out immediately: access token, refresh token and login all fail
        send(get("/api/me"), accessToken, null)
                .andExpect(status().isUnauthorized());
        send(post("/api/auth/refresh"), null, Map.of("refreshToken", refreshToken))
                .andExpect(status().isUnauthorized());
        send(post("/api/auth/login"), null, Map.of("email", email, "password", PASSWORD))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));

        // 5. re-enabled: the customer can log in again and still has their address
        send(patch("/api/admin/users/" + customerPublicId + "/status"), adminToken, Map.of("active", true))
                .andExpect(status().isOk());
        String newToken = body(send(post("/api/auth/login"), null, Map.of("email", email, "password", PASSWORD))
                .andExpect(status().isOk()))
                .get("accessToken").asText();
        send(get("/api/addresses"), newToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    /** Admins can't self-register, so create one directly and log in through the API. */
    private String loginAsNewAdmin() throws Exception {
        String email = "e2e-admin-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        User admin = new User();
        admin.setEmail(email);
        admin.setPasswordHash(passwordEncoder.encode(PASSWORD));
        admin.setRole(Role.ADMIN);
        admin = userRepository.save(admin);

        UserProfile profile = new UserProfile();
        profile.setUser(admin);
        profile.setFullName("Shop Owner");
        userProfileRepository.save(profile);

        return body(send(post("/api/auth/login"), null, Map.of("email", email, "password", PASSWORD))
                .andExpect(status().isOk()))
                .get("accessToken").asText();
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String accessToken, Object body) throws Exception {
        if (accessToken != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json(body));
        }
        return mockMvc.perform(request);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static Map<String, Object> cityAddress() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("label", "Home");
        body.put("receiverName", "Sok Dara");
        body.put("phone", "012345678");
        body.put("deliveryType", "CITY");
        body.put("province", "Phnom Penh");
        body.put("latitude", "11.556400");
        body.put("longitude", "104.928200");
        body.put("street", "St. 13");
        return body;
    }
}
