package com.psar.user.controller;

import com.psar.common.security.AuthenticatedUser;
import com.psar.user.dto.request.ChangePasswordRequest;
import com.psar.user.dto.request.LoginRequest;
import com.psar.user.dto.request.RefreshTokenRequest;
import com.psar.user.dto.request.RegisterRequest;
import com.psar.user.dto.response.AuthResponse;
import com.psar.user.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth")
@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Register a customer account and log in")
    @SecurityRequirements
    @PostMapping("/api/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @Operation(summary = "Log in with email and password")
    @SecurityRequirements
    @PostMapping("/api/auth/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @Operation(summary = "Exchange a refresh token for a new token pair (the old one is revoked)")
    @SecurityRequirements
    @PostMapping("/api/auth/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refresh(request);
    }

    @Operation(summary = "Revoke a refresh token")
    @SecurityRequirements
    @PostMapping("/api/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request);
    }

    @Operation(summary = "Change my password (logs out all other sessions)")
    @PutMapping("/api/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal AuthenticatedUser me,
                               @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(me, request);
    }
}
