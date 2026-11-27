package com.psar.user.repository;

import com.psar.user.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByToken(String token);

    /** Logout everywhere, or a reused (stolen) token was detected during rotation. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken t set t.revoked = true where t.user.id = :userId and t.revoked = false")
    int revokeAllByUserId(@Param("userId") Long userId);

    /**
     * Revokes one token only if it is still active. Returns 0 when another request already
     * revoked it, so two concurrent refreshes with the same token can't both succeed.
     */
    @Modifying(flushAutomatically = true)
    @Query("update RefreshToken t set t.revoked = true where t.id = :id and t.revoked = false")
    int revokeIfActive(@Param("id") Long id);

    /** Used by the daily token cleanup job. */
    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
