package com.psar.support;

import com.psar.common.security.AuthenticatedUser;
import com.psar.user.entity.User;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/**
 * Fake login for MockMvc tests, so tests don't depend on real JWTs:
 * <pre>
 * mockMvc.perform(get("/api/me").with(TestAuth.as(savedUser)))
 * mockMvc.perform(get("/api/admin/users").with(TestAuth.asRole(1L, "ADMIN")))
 * </pre>
 */
public final class TestAuth {

    private TestAuth() {
    }

    /** Log in as a saved user (id and publicId must be real for ownership checks). */
    public static RequestPostProcessor as(User user) {
        return as(new AuthenticatedUser(user.getId(), user.getPublicId(), user.getEmail(), user.getRole().name()));
    }

    /** Log in as a made-up user with the given role, e.g. for 403 checks. */
    public static RequestPostProcessor asRole(Long userId, String role) {
        return as(new AuthenticatedUser(userId, UUID.randomUUID(), role.toLowerCase() + "@test.local", role));
    }

    public static RequestPostProcessor as(AuthenticatedUser user) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(
                user, null, List.of(new SimpleGrantedAuthority(user.authority()))));
    }
}
