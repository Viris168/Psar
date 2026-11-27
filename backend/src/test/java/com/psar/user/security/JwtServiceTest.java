package com.psar.user.security;

import com.psar.common.exception.ErrorCode;
import com.psar.common.exception.UnauthorizedException;
import com.psar.user.entity.Role;
import com.psar.user.entity.User;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-that-is-at-least-32-bytes";

    private final JwtService jwtService = new JwtService(SECRET, Duration.ofMinutes(15));

    @Test
    void constructor_secretShorterThan32Bytes_failsAtStartup() {
        assertThatThrownBy(() -> new JwtService("too-short", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void parseAccessToken_tokenFromCreate_returnsPublicId() {
        User user = user();

        String token = jwtService.createAccessToken(user);

        assertThat(jwtService.parseAccessToken(token)).isEqualTo(user.getPublicId());
    }

    @Test
    void parseAccessToken_expiredToken_throwsInvalidToken() {
        JwtService expiredIssuer = new JwtService(SECRET, Duration.ofSeconds(-60));
        String token = expiredIssuer.createAccessToken(user());

        assertInvalidToken(token);
    }

    @Test
    void parseAccessToken_signedWithOtherSecret_throwsInvalidToken() {
        JwtService other = new JwtService("another-secret-that-is-also-32-bytes-long!", Duration.ofMinutes(15));

        assertInvalidToken(other.createAccessToken(user()));
    }

    @Test
    void parseAccessToken_garbage_throwsInvalidToken() {
        assertInvalidToken("not-a-jwt");
        assertInvalidToken("");
    }

    private void assertInvalidToken(String token) {
        assertThatThrownBy(() -> jwtService.parseAccessToken(token))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    private static User user() {
        User user = new User();
        user.setEmail("jwt@test.local");
        user.setRole(Role.CUSTOMER);
        return user;
    }
}
