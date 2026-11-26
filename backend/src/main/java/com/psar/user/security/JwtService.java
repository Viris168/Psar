package com.psar.user.security;

import com.psar.common.exception.UnauthorizedException;
import com.psar.user.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and verifies HS256 access tokens. {@code sub} = user publicId, claim {@code role}.
 * Refresh tokens are opaque random values handled by AuthService, not JWTs.
 */
@Service
public class JwtService {

    static final String ROLE_CLAIM = "role";
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final Duration accessTokenTtl;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.access-token-ttl}") Duration accessTokenTtl) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "app.jwt.secret (JWT_SECRET) must be at least " + MIN_SECRET_BYTES + " bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtl = accessTokenTtl;
    }

    public String createAccessToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getPublicId().toString())
                .claim(ROLE_CLAIM, user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenTtl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** Returns the user's publicId; throws {@link UnauthorizedException#invalidToken()} on a bad or expired token. */
    public UUID parseAccessToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return UUID.fromString(claims.getSubject());
        } catch (JwtException | IllegalArgumentException | NullPointerException ex) {
            throw UnauthorizedException.invalidToken();
        }
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }
}
