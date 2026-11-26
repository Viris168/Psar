package com.psar.user.security;

import com.psar.common.exception.UnauthorizedException;
import com.psar.common.security.AuthenticatedUser;
import com.psar.user.entity.User;
import com.psar.user.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Authenticates {@code Authorization: Bearer <jwt>} requests.
 * <ul>
 *   <li>No Bearer header: the request continues untouched (public paths work, and an
 *       Authentication set by tests is kept).</li>
 *   <li>Valid token: the user is reloaded from the DB on every request, so a disabled
 *       account is locked out immediately. Principal = {@link AuthenticatedUser}, authority ROLE_&lt;role&gt;.</li>
 *   <li>Bad/expired token, unknown or inactive user: the request continues unauthenticated, so a
 *       protected path gets the JSON 401 from SecurityErrorHandler, while public paths
 *       (login, refresh) still work when a client sends a stale token.</li>
 * </ul>
 * Registered only inside the security chain (see SecurityConfig), not as a servlet filter.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        Optional<User> user = resolveActiveUser(token);
        if (user.isPresent()) {
            authenticate(user.get());
        } else {
            // a bad token never counts as a login, even if something else set one earlier
            contextHolder.clearContext();
        }
        chain.doFilter(request, response);
    }

    private Optional<User> resolveActiveUser(String token) {
        if (token.isEmpty()) {
            return Optional.empty();
        }
        UUID publicId;
        try {
            publicId = jwtService.parseAccessToken(token);
        } catch (UnauthorizedException ex) {
            return Optional.empty();
        }
        return userRepository.findByPublicId(publicId).filter(User::isActive);
    }

    private void authenticate(User user) {
        AuthenticatedUser principal = new AuthenticatedUser(
                user.getId(), user.getPublicId(), user.getEmail(), user.getRole().name());
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority(principal.authority())));
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        contextHolder.setContext(context);
    }
}
