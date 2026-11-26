package com.psar.user.repository;

import com.psar.user.entity.Address;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/** Every lookup is scoped to the owning user: a missing match means 404. */
public interface AddressRepository extends JpaRepository<Address, Long> {

    Optional<Address> findByPublicIdAndUserId(UUID publicId, Long userId);

    Page<Address> findAllByUserId(Long userId, Pageable pageable);

    Optional<Address> findByUserIdAndDefaultAddressTrue(Long userId);

    long countByUserId(Long userId);

    /** Call before marking another address as default (DB allows one default per user). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Address a set a.defaultAddress = false where a.user.id = :userId and a.defaultAddress = true")
    int clearDefault(@Param("userId") Long userId);
}
