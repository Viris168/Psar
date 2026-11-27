package com.psar.inventory.repository;

import com.psar.inventory.entity.PurchaseOrderStatus;
import com.psar.inventory.entity.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SupplierRepository extends JpaRepository<Supplier, Long>, JpaSpecificationExecutor<Supplier> {

    Optional<Supplier> findByPublicId(UUID publicId);

    /** Kept here (not in PurchaseOrderRepository) so the supplier rules live with suppliers. */
    @Query("""
            select count(po) > 0 from PurchaseOrder po
            where po.supplier.id = :supplierId and po.status = :status""")
    boolean hasPurchaseOrdersWithStatus(@Param("supplierId") Long supplierId,
                                        @Param("status") PurchaseOrderStatus status);
}
