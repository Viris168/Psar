package com.psar.common.security;

import com.psar.support.IntegrationTest;
import com.psar.support.TestAuth;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Proves the Step 0 test setup: TestAuth gets past security, no auth gets the JSON 401. */
class TestAuthTest extends IntegrationTest {

    // a path no controller will ever map: 404 means security let the request through
    private static final String UNMAPPED = "/api/test-auth-probe";

    @Test
    void protectedPath_noAuth_returns401Json() throws Exception {
        mockMvc.perform(get(UNMAPPED))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void protectedPath_withTestAuth_passesSecurity() throws Exception {
        mockMvc.perform(get(UNMAPPED).with(TestAuth.asRole(1L, "CUSTOMER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminPath_customerRole_returns403Json() throws Exception {
        mockMvc.perform(get("/api/admin/test-auth-probe").with(TestAuth.asRole(1L, "CUSTOMER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
