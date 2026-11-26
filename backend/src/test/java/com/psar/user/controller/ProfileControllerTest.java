package com.psar.user.controller;

import com.psar.support.IntegrationTest;
import com.psar.support.TestAuth;
import com.psar.user.entity.Role;
import com.psar.user.entity.User;
import com.psar.user.entity.UserProfile;
import com.psar.user.repository.UserProfileRepository;
import com.psar.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProfileControllerTest extends IntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Test
    void getMe_withProfile_returnsAccountAndProfile() throws Exception {
        User user = saveUser(Role.CUSTOMER);
        saveProfile(user, "Sok Dara", 0);

        mockMvc.perform(get("/api/me").with(TestAuth.as(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(user.getPublicId().toString()))
                .andExpect(jsonPath("$.email").value(user.getEmail()))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.fullName").value("Sok Dara"))
                .andExpect(jsonPath("$.language").value("EN"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    void getMe_staffUser_returnsProfile() throws Exception {
        User user = saveUser(Role.STAFF);
        saveProfile(user, "Staff Member", 0);

        mockMvc.perform(get("/api/me").with(TestAuth.as(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("STAFF"));
    }

    @Test
    void getMe_noProfile_returns404() throws Exception {
        User user = saveUser(Role.CUSTOMER);

        mockMvc.perform(get("/api/me").with(TestAuth.as(user)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void getMe_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateProfile_validRequest_updatesAndKeepsPointsBalance() throws Exception {
        User user = saveUser(Role.CUSTOMER);
        saveProfile(user, "Old Name", 250);

        Map<String, Object> body = new HashMap<>();
        body.put("fullName", "New Name");
        body.put("fullNameKh", "ឈ្មោះថ្មី");
        body.put("gender", "FEMALE");
        body.put("dateOfBirth", "1995-04-13");
        body.put("phone", "012345678");
        body.put("language", "KM");
        // not editable: must be ignored
        body.put("pointsBalance", 99999);
        body.put("position", "MANAGER");

        mockMvc.perform(put("/api/me/profile").with(TestAuth.as(user))
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("New Name"))
                .andExpect(jsonPath("$.gender").value("FEMALE"))
                .andExpect(jsonPath("$.language").value("KM"))
                .andExpect(jsonPath("$.pointsBalance").value(250))
                .andExpect(jsonPath("$.position").doesNotExist());

        UserProfile saved = userProfileRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(saved.getFullName()).isEqualTo("New Name");
        assertThat(saved.getPhone()).isEqualTo("012345678");
        assertThat(saved.getPointsBalance()).isEqualTo(250);
        assertThat(saved.getPosition()).isNull();
    }

    @Test
    void updateProfile_blankFullName_returns400() throws Exception {
        User user = saveUser(Role.CUSTOMER);
        saveProfile(user, "Name", 0);

        mockMvc.perform(put("/api/me/profile").with(TestAuth.as(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("fullName", " ", "language", "EN"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fullName").exists());
    }

    private User saveUser(Role role) {
        User user = new User();
        user.setEmail("profile-" + UUID.randomUUID() + "@test.local");
        user.setPasswordHash("not-a-real-hash");
        user.setRole(role);
        return userRepository.save(user);
    }

    private void saveProfile(User user, String fullName, int points) {
        UserProfile profile = new UserProfile();
        profile.setUser(user);
        profile.setFullName(fullName);
        profile.setPointsBalance(points);
        userProfileRepository.save(profile);
    }
}
