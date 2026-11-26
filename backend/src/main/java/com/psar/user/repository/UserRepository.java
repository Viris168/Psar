package com.psar.user.repository;

import com.psar.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Emails are stored lowercase: normalize before calling the email lookups. */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByPublicId(UUID publicId);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
