package com.psar.user.repository;

import com.psar.user.entity.Role;
import com.psar.user.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Emails are stored lowercase: normalize before calling the email lookups. */
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    Optional<User> findByPublicId(UUID publicId);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * Locks the active users with this role (SELECT ... FOR UPDATE, in id order to avoid deadlocks), so two admins
     * demoting/disabling each other at the same time can't leave the shop without an ADMIN.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<User> findAllByRoleAndActiveTrueOrderByIdAsc(Role role);

    /** Ids of the active users with one of these roles, e.g. who gets a low-stock notification. */
    @Query("select u.id from User u where u.active = true and u.role in :roles order by u.id")
    List<Long> findActiveIdsByRoleIn(@Param("roles") Collection<Role> roles);
}
