package com.psar.user.service;

import com.psar.common.exception.BusinessRuleException;
import com.psar.common.exception.ConflictException;
import com.psar.common.exception.ErrorCode;
import com.psar.common.exception.ForbiddenException;
import com.psar.common.exception.UnauthorizedException;
import com.psar.common.security.AuthenticatedUser;
import com.psar.user.dto.request.ChangePasswordRequest;
import com.psar.user.dto.request.LoginRequest;
import com.psar.user.dto.request.RefreshTokenRequest;
import com.psar.user.dto.request.RegisterRequest;
import com.psar.user.dto.response.AuthResponse;
import com.psar.user.entity.RefreshToken;
import com.psar.user.entity.Role;
import com.psar.user.entity.User;
import com.psar.user.entity.UserProfile;
import com.psar.user.repository.RefreshTokenRepository;
import com.psar.user.repository.UserProfileRepository;
import com.psar.user.repository.UserRepository;
import com.psar.user.security.JwtService;
import com.psar.user.security.RefreshTokenHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.jwt.refresh-token-ttl}")
    private Duration refreshTokenTtl;

    /** Compared against when the email is unknown, so both failure paths cost one BCrypt check. */
    private String dummyHash;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException(ErrorCode.EMAIL_ALREADY_EXISTS, "Email is already registered");
        }

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(Role.CUSTOMER);
        user = userRepository.save(user);

        UserProfile profile = new UserProfile();
        profile.setUser(user);
        profile.setFullName(request.fullName().trim());
        userProfileRepository.save(profile);

        return issueTokens(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        Optional<User> found = userRepository.findByEmail(normalizeEmail(request.email()));
        if (found.isEmpty()) {
            passwordEncoder.matches(request.password(), dummyHash());
            throw UnauthorizedException.invalidCredentials();
        }
        User user = found.get();
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw UnauthorizedException.invalidCredentials();
        }
        if (!user.isActive()) {
            throw ForbiddenException.accountDisabled();
        }
        user.setLastLoginAt(Instant.now());
        return issueTokens(user);
    }

    /**
     * Rotation: the presented token is revoked and a new pair is issued. Presenting an
     * already-revoked token means it was stolen or replayed: every token of that user is
     * revoked (kept on rollback via noRollbackFor) and the call fails with 401.
     * Losing a race to a concurrent refresh of the same token (e.g. two tabs) only fails
     * this call, so the winner's new session survives.
     */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public AuthResponse refresh(RefreshTokenRequest request) {
        RefreshToken token = refreshTokenRepository.findByToken(RefreshTokenHasher.hash(request.refreshToken()))
                .orElseThrow(UnauthorizedException::invalidToken);
        User user = token.getUser();

        if (token.isRevoked()) {
            refreshTokenRepository.revokeAllByUserId(user.getId());
            throw UnauthorizedException.invalidToken();
        }
        if (refreshTokenRepository.revokeIfActive(token.getId()) == 0) {
            throw UnauthorizedException.invalidToken();
        }
        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw UnauthorizedException.invalidToken();
        }
        if (!user.isActive()) {
            throw ForbiddenException.accountDisabled();
        }
        return issueTokens(user);
    }

    /** Idempotent: an unknown or already-revoked token still ends in 204. */
    @Transactional
    public void logout(RefreshTokenRequest request) {
        refreshTokenRepository.findByToken(RefreshTokenHasher.hash(request.refreshToken()))
                .ifPresent(token -> refreshTokenRepository.revokeIfActive(token.getId()));
    }

    /** Wrong current password -> 422 (not 401, so clients don't treat it as an expired session). */
    @Transactional
    public void changePassword(AuthenticatedUser me, ChangePasswordRequest request) {
        User user = userRepository.findById(me.userId())
                .orElseThrow(() -> new UnauthorizedException("Authentication required"));
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessRuleException("Current password is incorrect",
                    Map.of("currentPassword", "is incorrect"));
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        refreshTokenRepository.revokeAllByUserId(user.getId());
    }

    private AuthResponse issueTokens(User user) {
        String rawRefreshToken = RefreshTokenHasher.newRawToken();
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setToken(RefreshTokenHasher.hash(rawRefreshToken));
        refreshToken.setExpiresAt(Instant.now().plus(refreshTokenTtl));
        refreshTokenRepository.save(refreshToken);

        return AuthResponse.bearer(
                jwtService.createAccessToken(user),
                rawRefreshToken,
                jwtService.getAccessTokenTtl().toSeconds());
    }

    private String dummyHash() {
        if (dummyHash == null) {
            dummyHash = passwordEncoder.encode("timing-equalizer-not-a-real-password");
        }
        return dummyHash;
    }

    static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
