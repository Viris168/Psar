package com.psar.user.repository;

import com.psar.user.entity.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {

    Optional<UserProfile> findByUserId(Long userId);

    /** Profile plus its user in one query (ProfileResponse needs email, role, publicId). */
    @Query("select p from UserProfile p join fetch p.user u where u.id = :userId")
    Optional<UserProfile> findByUserIdWithUser(@Param("userId") Long userId);
}
