package com.psar.user.controller;

import com.psar.common.security.AuthenticatedUser;
import com.psar.user.dto.request.UpdateProfileRequest;
import com.psar.user.dto.response.ProfileResponse;
import com.psar.user.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The logged-in user's own profile, any role. (/api/me/password lives in AuthController.) */
@Tag(name = "Profile")
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    @Operation(summary = "Get the current user's account and profile")
    @GetMapping
    public ProfileResponse getMe(@AuthenticationPrincipal AuthenticatedUser me) {
        return profileService.getMe(me);
    }

    @Operation(summary = "Update the current user's profile")
    @PutMapping("/profile")
    public ProfileResponse updateMe(@AuthenticationPrincipal AuthenticatedUser me,
                                    @Valid @RequestBody UpdateProfileRequest request) {
        return profileService.updateMe(me, request);
    }
}
