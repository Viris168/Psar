package com.psar.user.controller;

import com.psar.support.IntegrationTest;
import com.psar.support.TestAuth;
import com.psar.user.entity.RefreshToken;
import com.psar.user.entity.Role;
import com.psar.user.entity.User;
import com.psar.user.entity.UserProfile;
import com.psar.user.repository.RefreshTokenRepository;
import com.psar.user.repository.UserProfileRepository;
import com.psar.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.http.MediaType.APPLICATION_JSON;

class AdminUserControllerTest extends IntegrationTest {

    private static final String USERS = "/api/admin/users";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // ---- access ------------------------------------------------------------

    @Test
    void adminUsers_managerRole_returns403() throws Exception {
        mockMvc.perform(get(USERS).with(TestAuth.asRole(1L, "MANAGER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void adminUsers_staffRole_returns403() throws Exception {
        mockMvc.perform(patch(USERS + "/" + UUID.randomUUID() + "/status").with(TestAuth.asRole(1L, "STAFF"))
                        .contentType(APPLICATION_JSON).content(json(Map.of("active", false))))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminUsers_noAuth_returns401() throws Exception {
        mockMvc.perform(get(USERS)).andExpect(status().isUnauthorized());
    }

    // ---- list / get --------------------------------------------------------

    @Test
    void list_roleAndEmailFilter_returnsMatchingUsersOnly() throws Exception {
        String tag = "lst" + shortId();
        User staff = saveUser(Role.STAFF, tag + "-staff@test.local");
        User customer = saveUser(Role.CUSTOMER, tag + "-customer@test.local");
        User admin = saveUser(Role.ADMIN, "list-admin-" + shortId() + "@test.local");

        mockMvc.perform(get(USERS).with(TestAuth.as(admin))
                        .param("role", "STAFF").param("q", tag.toUpperCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].publicId").value(staff.getPublicId().toString()))
                .andExpect(jsonPath("$.content[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$.content[0].id").doesNotExist())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get(USERS).with(TestAuth.as(admin)).param("q", tag))
                .andExpect(jsonPath("$.content[*].publicId", hasItem(customer.getPublicId().toString())))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void list_hugePageSizeAndUnknownSort_capsAt100AndIgnoresSort() throws Exception {
        mockMvc.perform(get(USERS).with(TestAuth.asRole(1L, "ADMIN"))
                        .param("size", "500").param("sort", "passwordHash,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void get_existingUser_returnsUser() throws Exception {
        User user = saveUser(Role.CUSTOMER, "get-" + shortId() + "@test.local");

        mockMvc.perform(get(USERS + "/" + user.getPublicId()).with(TestAuth.asRole(1L, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(user.getEmail()))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void get_unknownPublicId_returns404() throws Exception {
        mockMvc.perform(get(USERS + "/" + UUID.randomUUID()).with(TestAuth.asRole(1L, "ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ---- create ------------------------------------------------------------

    @Test
    void createStaff_validRequest_createsUserAndProfileWithHashedPassword() throws Exception {
        String email = "New.Staff-" + shortId() + "@Test.Local";
        Map<String, Object> body = Map.of(
                "email", email, "password", "secret-pass-1",
                "fullName", "Sok Dara", "role", "MANAGER", "position", "Store manager");

        mockMvc.perform(post(USERS).with(TestAuth.asRole(1L, "ADMIN"))
                        .contentType(APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email.toLowerCase()))
                .andExpect(jsonPath("$.role").value("MANAGER"))
                .andExpect(jsonPath("$.active").value(true));

        User saved = userRepository.findByEmail(email.toLowerCase()).orElseThrow();
        assertThat(passwordEncoder.matches("secret-pass-1", saved.getPasswordHash())).isTrue();
        UserProfile profile = userProfileRepository.findByUserId(saved.getId()).orElseThrow();
        assertThat(profile.getFullName()).isEqualTo("Sok Dara");
        assertThat(profile.getPosition()).isEqualTo("Store manager");
    }

    @Test
    void createStaff_customerRole_returns400or422() throws Exception {
        String email = "cust-" + shortId() + "@test.local";

        mockMvc.perform(post(USERS).with(TestAuth.asRole(1L, "ADMIN"))
                        .contentType(APPLICATION_JSON).content(json(staffBody(email, "CUSTOMER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.details.role").exists());

        assertThat(userRepository.existsByEmail(email)).isFalse();
    }

    @Test
    void createStaff_duplicateEmailDifferentCase_returns409() throws Exception {
        User existing = saveUser(Role.STAFF, "dup-" + shortId() + "@test.local");

        mockMvc.perform(post(USERS).with(TestAuth.asRole(1L, "ADMIN"))
                        .contentType(APPLICATION_JSON).content(json(staffBody(existing.getEmail().toUpperCase(), "STAFF"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
    }

    @Test
    void createStaff_invalidBody_returns400() throws Exception {
        Map<String, Object> body = Map.of("email", "not-an-email", "password", "short", "fullName", "");

        mockMvc.perform(post(USERS).with(TestAuth.asRole(1L, "ADMIN"))
                        .contentType(APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.email").exists())
                .andExpect(jsonPath("$.details.password").exists())
                .andExpect(jsonPath("$.details.role").exists());
    }

    // ---- change role -------------------------------------------------------

    @Test
    void changeRole_staffToManager_returnsUpdatedUser() throws Exception {
        User staff = saveUser(Role.STAFF, "promote-" + shortId() + "@test.local");

        mockMvc.perform(patch(USERS + "/" + staff.getPublicId() + "/role").with(TestAuth.asRole(1L, "ADMIN"))
                        .contentType(APPLICATION_JSON).content(json(Map.of("role", "MANAGER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MANAGER"));

        assertThat(userRepository.findById(staff.getId()).orElseThrow().getRole()).isEqualTo(Role.MANAGER);
    }

    @Test
    void changeRole_demoteSelf_returns422() throws Exception {
        User admin = saveUser(Role.ADMIN, "self-demote-" + shortId() + "@test.local");

        mockMvc.perform(patch(USERS + "/" + admin.getPublicId() + "/role").with(TestAuth.as(admin))
                        .contentType(APPLICATION_JSON).content(json(Map.of("role", "STAFF"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));

        assertThat(userRepository.findById(admin.getId()).orElseThrow().getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    void changeRole_otherAdminWhileAnotherAdminActive_succeeds() throws Exception {
        User caller = saveUser(Role.ADMIN, "demoter-" + shortId() + "@test.local");
        User target = saveUser(Role.ADMIN, "demoted-" + shortId() + "@test.local");

        mockMvc.perform(patch(USERS + "/" + target.getPublicId() + "/role").with(TestAuth.as(caller))
                        .contentType(APPLICATION_JSON).content(json(Map.of("role", "STAFF"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("STAFF"));
    }

    /**
     * The test transaction deactivates every other admin (made by any test class), so the target is
     * the only active ADMIN. It rolls back afterwards, so no other test sees this state.
     */
    @Test
    @Transactional
    void changeRole_lastAdmin_returns422() throws Exception {
        User lastAdmin = makeOnlyActiveAdmin("last-admin-role-" + shortId() + "@test.local");

        // caller is an admin who isn't an active ADMIN row (e.g. a stale token): only the last-admin rule applies
        mockMvc.perform(patch(USERS + "/" + lastAdmin.getPublicId() + "/role").with(TestAuth.asRole(-1L, "ADMIN"))
                        .contentType(APPLICATION_JSON).content(json(Map.of("role", "MANAGER"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));
    }

    // ---- change status -----------------------------------------------------

    @Test
    void changeStatus_disableSelf_returns422() throws Exception {
        User admin = saveUser(Role.ADMIN, "self-disable-" + shortId() + "@test.local");

        mockMvc.perform(patch(USERS + "/" + admin.getPublicId() + "/status").with(TestAuth.as(admin))
                        .contentType(APPLICATION_JSON).content(json(Map.of("active", false))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));

        assertThat(userRepository.findById(admin.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    @Transactional
    void changeStatus_lastAdmin_returns422() throws Exception {
        User lastAdmin = makeOnlyActiveAdmin("last-admin-status-" + shortId() + "@test.local");

        mockMvc.perform(patch(USERS + "/" + lastAdmin.getPublicId() + "/status").with(TestAuth.asRole(-1L, "ADMIN"))
                        .contentType(APPLICATION_JSON).content(json(Map.of("active", false))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));
    }

    @Test
    void changeStatus_disable_revokesRefreshTokens() throws Exception {
        User customer = saveUser(Role.CUSTOMER, "disable-" + shortId() + "@test.local");
        saveRefreshToken(customer);
        saveRefreshToken(customer);
        User admin = saveUser(Role.ADMIN, "disabler-" + shortId() + "@test.local");

        mockMvc.perform(patch(USERS + "/" + customer.getPublicId() + "/status").with(TestAuth.as(admin))
                        .contentType(APPLICATION_JSON).content(json(Map.of("active", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        assertThat(userRepository.findById(customer.getId()).orElseThrow().isActive()).isFalse();
        assertThat(refreshTokenRepository.findAll())
                .filteredOn(t -> t.getUser().getId().equals(customer.getId()))
                .hasSize(2)
                .allMatch(RefreshToken::isRevoked);
    }

    @Test
    void changeStatus_enableDisabledUser_reactivates() throws Exception {
        User user = saveUser(Role.STAFF, "enable-" + shortId() + "@test.local");
        user.setActive(false);
        userRepository.save(user);

        mockMvc.perform(patch(USERS + "/" + user.getPublicId() + "/status").with(TestAuth.asRole(1L, "ADMIN"))
                        .contentType(APPLICATION_JSON).content(json(Map.of("active", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void changeStatus_missingActive_returns400() throws Exception {
        User user = saveUser(Role.STAFF, "noactive-" + shortId() + "@test.local");

        mockMvc.perform(patch(USERS + "/" + user.getPublicId() + "/status").with(TestAuth.asRole(1L, "ADMIN"))
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.active").exists());
    }

    // ---- helpers -----------------------------------------------------------

    /** Must run inside the test transaction: the other admins come back when it rolls back. */
    private User makeOnlyActiveAdmin(String email) {
        userRepository.findAllByRoleAndActiveTrueOrderByIdAsc(Role.ADMIN).forEach(admin -> admin.setActive(false));
        User lastAdmin = saveUser(Role.ADMIN, email);
        userRepository.flush();
        assertThat(userRepository.findAllByRoleAndActiveTrueOrderByIdAsc(Role.ADMIN))
                .extracting(User::getId).containsExactly(lastAdmin.getId());
        return lastAdmin;
    }

    private User saveUser(Role role, String email) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("not-used");
        user.setRole(role);
        return userRepository.save(user);
    }

    private void saveRefreshToken(User user) {
        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setToken("test-" + UUID.randomUUID());
        token.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
        refreshTokenRepository.save(token);
    }

    private static Map<String, Object> staffBody(String email, String role) {
        return Map.of("email", email, "password", "secret-pass-1", "fullName", "Test Person", "role", role);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
