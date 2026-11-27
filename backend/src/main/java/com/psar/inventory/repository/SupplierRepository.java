package com.psar.inventory.repository;

import com.psar.inventory.entity.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    Optional<Supplier> findByPublicId(UUID publicId);
}
