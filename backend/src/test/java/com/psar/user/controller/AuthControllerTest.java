package com.psar.user.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.psar.support.IntegrationTest;
import com.psar.support.TestAuth;
import com.psar.user.dto.request.ChangePasswordRequest;
import com.psar.user.dto.request.LoginRequest;
import com.psar.user.dto.request.RefreshTokenRequest;
import com.psar.user.dto.request.RegisterRequest;
import com.psar.user.entity.Role;
import com.psar.user.entity.User;
import com.psar.user.repository.RefreshTokenRepository;
import com.psar.user.repository.UserProfileRepository;
import com.psar.user.repository.UserRepository;
import com.psar.user.security.JwtService;
import com.psar.user.security.RefreshTokenHasher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerTest extends IntegrationTest {

    private static final String PASSWORD = "Password123!";
    // no controller maps this path: 404 means the JWT got through security
    private static final String PROTECTED_PROBE = "/api/auth-test-probe";

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserProfileRepository userProfileRepository;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private JwtService jwtService;

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    // ---------- register ----------

    @Test
    void register_validRequest_returns201WithTokensAndCreatesCustomerWithProfile() throws Exception {
        String email = uniqueEmail();

        register(email)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900));

        User user = userRepository.findByEmail(email).orElseThrow();
        assertThat(user.getRole()).isEqualTo(Role.CUSTOMER);
        assertThat(user.getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(userProfileRepository.findByUserId(user.getId()))
                .hasValueSatisfying(p -> assertThat(p.getFullName()).isEqualTo("Test User"));
    }

    @Test
    void register_duplicateEmail_returns409() throws Exception {
        String email = uniqueEmail();
        register(email).andExpect(status().isCreated());

        register(email.toUpperCase())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
    }

    @Test
    void register_mixedCaseEmail_storedLowercase() throws Exception {
        String local = "Mixed.Case." + UUID.randomUUID();
        register(local + "@Test.LOCAL").andExpect(status().isCreated());

        String expected = local.toLowerCase() + "@test.local";
        assertThat(userRepository.findByEmail(expected)).isPresent();

        login(local.toUpperCase() + "@TEST.local", PASSWORD).andExpect(status().isOk());
    }

    @Test
    void register_invalidBody_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRequest("not-an-email", "short", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---------- login ----------

    @Test
    void login_validCredentials_returnsTokensAndUpdatesLastLogin() throws Exception {
        String email = registerUser();

        login(email, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        assertThat(userRepository.findByEmail(email).orElseThrow().getLastLoginAt()).isNotNull();
    }

    @Test
    void login_wrongPassword_returns401() throws Exception {
        String email = registerUser();

        login(email, "WrongPassword1!")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void login_unknownEmail_returns401SameAsWrongPassword() throws Exception {
        login(uniqueEmail(), PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void login_disabledUser_returns403() throws Exception {
        String email = registerUser();
        disable(email);

        login(email, PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }

    // ---------- refresh / logout ----------

    @Test
    void refresh_validToken_rotatesAndOldTokenStopsWorking() throws Exception {
        String email = uniqueEmail();
        JsonNode first = body(register(email));
        String oldRefresh = first.get("refreshToken").asText();

        JsonNode rotated = body(refresh(oldRefresh).andExpect(status().isOk()));
        String newRefresh = rotated.get("refreshToken").asText();
        assertThat(newRefresh).isNotEqualTo(oldRefresh);
        assertThat(rotated.get("accessToken").asText()).isNotBlank();

        // only the hash is stored
        assertThat(refreshTokenRepository.findByToken(newRefresh)).isEmpty();
        assertThat(refreshTokenRepository.findByToken(RefreshTokenHasher.hash(newRefresh))).isPresent();

        refresh(oldRefresh)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    void refresh_reusedRevokedToken_revokesAllUserTokens() throws Exception {
        String email = registerUser();
        String sessionA = body(login(email, PASSWORD)).get("refreshToken").asText();
        String sessionB = body(login(email, PASSWORD)).get("refreshToken").asText();
        String sessionA2 = body(refresh(sessionA).andExpect(status().isOk())).get("refreshToken").asText();

        // replay of the already-rotated token
        refresh(sessionA).andExpect(status().isUnauthorized());

        assertRevoked(sessionB);
        assertRevoked(sessionA2);
        refresh(sessionA2).andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_unknownToken_returns401() throws Exception {
        refresh("does-not-exist")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    void refresh_withStaleBearerHeader_stillWorks() throws Exception {
        String refreshToken = body(register(uniqueEmail())).get("refreshToken").asText();

        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer expired.or.garbage")
                        .content(json(new RefreshTokenRequest(refreshToken))))
                .andExpect(status().isOk());
    }

    @Test
    void logout_validToken_returns204AndTokenStopsWorking() throws Exception {
        String refreshToken = body(register(uniqueEmail())).get("refreshToken").asText();

        logout(refreshToken).andExpect(status().isNoContent());

        assertRevoked(refreshToken);
        refresh(refreshToken).andExpect(status().isUnauthorized());
    }

    @Test
    void logout_unknownToken_returns204() throws Exception {
        logout("does-not-exist").andExpect(status().isNoContent());
    }

    // ---------- JWT filter ----------

    @Test
    void protectedEndpoint_validToken_passesSecurity() throws Exception {
        String accessToken = body(register(uniqueEmail())).get("accessToken").asText();

        mockMvc.perform(get(PROTECTED_PROBE).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void protectedEndpoint_noToken_returns401() throws Exception {
        mockMvc.perform(get(PROTECTED_PROBE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void protectedEndpoint_expiredToken_returns401() throws Exception {
        String email = registerUser();
        User user = userRepository.findByEmail(email).orElseThrow();
        String expired = new JwtService(jwtSecret, Duration.ofSeconds(-60)).createAccessToken(user);

        mockMvc.perform(get(PROTECTED_PROBE).header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void protectedEndpoint_malformedToken_returns401() throws Exception {
        mockMvc.perform(get(PROTECTED_PROBE).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpoint_userDisabledAfterLogin_returns401() throws Exception {
        String email = uniqueEmail();
        String accessToken = body(register(email)).get("accessToken").asText();
        disable(email);

        mockMvc.perform(get(PROTECTED_PROBE).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminEndpoint_customerToken_returns403() throws Exception {
        String accessToken = body(register(uniqueEmail())).get("accessToken").asText();

        mockMvc.perform(get("/api/admin/auth-test-probe").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void adminEndpoint_adminToken_passesSecurity() throws Exception {
        String email = registerUser();
        User user = userRepository.findByEmail(email).orElseThrow();
        user.setRole(Role.ADMIN);
        userRepository.save(user);
        String accessToken = jwtService.createAccessToken(user);

        mockMvc.perform(get("/api/admin/auth-test-probe").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isNotFound());
    }

    // ---------- change password ----------

    @Test
    void changePassword_validRequest_returns204AndRevokesRefreshTokens() throws Exception {
        String email = uniqueEmail();
        JsonNode tokens = body(register(email));
        String accessToken = tokens.get("accessToken").asText();
        String refreshToken = tokens.get("refreshToken").asText();

        mockMvc.perform(put("/api/me/password").contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .content(json(new ChangePasswordRequest(PASSWORD, "NewPassword456!"))))
                .andExpect(status().isNoContent());

        assertRevoked(refreshToken);
        login(email, PASSWORD).andExpect(status().isUnauthorized());
        login(email, "NewPassword456!").andExpect(status().isOk());
    }

    @Test
    void changePassword_wrongCurrent_returns422() throws Exception {
        String email = registerUser();
        User user = userRepository.findByEmail(email).orElseThrow();

        mockMvc.perform(put("/api/me/password").with(TestAuth.as(user)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ChangePasswordRequest("WrongPassword1!", "NewPassword456!"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"))
                .andExpect(jsonPath("$.details.currentPassword").exists());

        login(email, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void changePassword_noAuth_returns401() throws Exception {
        mockMvc.perform(put("/api/me/password").contentType(MediaType.APPLICATION_JSON)
                        .content(json(new ChangePasswordRequest(PASSWORD, "NewPassword456!"))))
                .andExpect(status().isUnauthorized());
    }

    // ---------- helpers ----------

    private static String uniqueEmail() {
        return "auth-" + UUID.randomUUID() + "@test.local";
    }

    private String registerUser() throws Exception {
        String email = uniqueEmail();
        register(email).andExpect(status().isCreated());
        return email;
    }

    private ResultActions register(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json(new RegisterRequest(email, PASSWORD, "Test User"))));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json(new LoginRequest(email, password))));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(json(new RefreshTokenRequest(refreshToken))));
    }

    private ResultActions logout(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .content(json(new RefreshTokenRequest(refreshToken))));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private void disable(String email) {
        User user = userRepository.findByEmail(email).orElseThrow();
        user.setActive(false);
        userRepository.save(user);
    }

    private void assertRevoked(String rawRefreshToken) {
        assertThat(refreshTokenRepository.findByToken(RefreshTokenHasher.hash(rawRefreshToken)))
                .hasValueSatisfying(t -> assertThat(t.isRevoked()).isTrue());
    }
}
