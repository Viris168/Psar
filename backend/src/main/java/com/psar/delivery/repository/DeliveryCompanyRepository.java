package com.psar.delivery.repository;

import com.psar.delivery.entity.DeliveryCompany;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeliveryCompanyRepository extends JpaRepository<DeliveryCompany, Long> {

    Page<DeliveryCompany> findAllByActiveTrue(Pageable pageable);

    Optional<DeliveryCompany> findByIdAndActiveTrue(Long id);

    boolean existsByIdAndActiveTrue(Long id);
}
