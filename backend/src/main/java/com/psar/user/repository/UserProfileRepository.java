package com.psar.user.repository;

import com.psar.user.entity.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {

    Optional<UserProfile> findByUserId(Long userId);

    /** One query for a whole page of users (admin list), instead of one per user. */
    List<UserProfile> findAllByUserIdIn(Collection<Long> userIds);

    /** Profile plus its user in one query (ProfileResponse needs email, role, publicId). */
    @Query("select p from UserProfile p join fetch p.user u where u.id = :userId")
    Optional<UserProfile> findByUserIdWithUser(@Param("userId") Long userId);

    /** Drivers: active STAFF whose position is "Driver" (any case). */
    @Query("""
            select u.id from UserProfile p join p.user u
            where u.active = true and u.role = com.psar.user.entity.Role.STAFF and lower(p.position) = 'driver'
            order by u.id""")
    List<Long> findActiveDriverIds();
}
