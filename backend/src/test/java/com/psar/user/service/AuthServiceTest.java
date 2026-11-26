package com.psar.user.service;

import com.psar.common.exception.UnauthorizedException;
import com.psar.user.dto.request.RefreshTokenRequest;
import com.psar.user.entity.RefreshToken;
import com.psar.user.entity.User;
import com.psar.user.repository.RefreshTokenRepository;
import com.psar.user.security.RefreshTokenHasher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The lost-race path can't be hit reliably through HTTP: it needs the token to be read as
 * active and then revoked by another transaction before this one updates it.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @InjectMocks
    private AuthService authService;

    @Test
    void refresh_lostRaceToConcurrentRefresh_failsWithoutRevokingOtherSessions() {
        RefreshToken token = activeToken();
        when(refreshTokenRepository.findByToken(RefreshTokenHasher.hash("raw"))).thenReturn(Optional.of(token));
        when(refreshTokenRepository.revokeIfActive(token.getId())).thenReturn(0);

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest("raw")))
                .isInstanceOf(UnauthorizedException.class);
        verify(refreshTokenRepository, never()).revokeAllByUserId(anyLong());
    }

    @Test
    void refresh_alreadyRevokedToken_revokesAllUserTokens() {
        RefreshToken token = activeToken();
        token.setRevoked(true);
        when(refreshTokenRepository.findByToken(RefreshTokenHasher.hash("raw"))).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest("raw")))
                .isInstanceOf(UnauthorizedException.class);
        verify(refreshTokenRepository).revokeAllByUserId(token.getUser().getId());
    }

    private static RefreshToken activeToken() {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", 7L);
        RefreshToken token = new RefreshToken();
        token.setId(42L);
        token.setUser(user);
        return token;
    }
}
