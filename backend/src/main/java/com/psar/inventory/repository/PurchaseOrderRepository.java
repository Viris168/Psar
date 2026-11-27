package com.psar.inventory.repository;

import com.psar.inventory.entity.PurchaseOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long>,
        JpaSpecificationExecutor<PurchaseOrder> {

    Optional<PurchaseOrder> findByPoNo(String poNo);

    /** SELECT ... FOR UPDATE: a second receive/cancel waits, then sees the new status. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select po from PurchaseOrder po where po.poNo = :poNo")
    Optional<PurchaseOrder> findByPoNoForUpdate(@Param("poNo") String poNo);

    /** Loads the supplier with the page (no N+1 in the list). */
    @Override
    @EntityGraph(attributePaths = "supplier")
    Page<PurchaseOrder> findAll(Specification<PurchaseOrder> spec, Pageable pageable);
}
