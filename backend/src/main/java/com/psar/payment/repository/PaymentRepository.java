package com.psar.payment.repository;

import com.psar.payment.entity.Payment;
import com.psar.payment.entity.PaymentProvider;
import com.psar.payment.entity.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, Long>, JpaSpecificationExecutor<Payment> {

    Optional<Payment> findByOrderId(Long orderId);

    Optional<Payment> findByPublicId(UUID publicId);

    /** SELECT … FOR UPDATE: confirmation locks the payment first, so two sources can't both apply it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.provider = :provider and p.providerRef = :providerRef")
    Optional<Payment> lockByProviderRef(@Param("provider") PaymentProvider provider,
                                        @Param("providerRef") String providerRef);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.orderId = :orderId")
    Optional<Payment> lockByOrderId(@Param("orderId") Long orderId);

    /**
     * The check job's work: pending gateway payments created before {@code createdBefore} and not checked since
     * {@code checkedBefore} (never-checked first). Ids only; each is confirmed in its own transaction.
     */
    @Query("""
            select p.id from Payment p
            where p.status = :status and p.provider is not null
              and p.createdAt < :createdBefore
              and (p.lastCheckedAt is null or p.lastCheckedAt < :checkedBefore)
            order by p.lastCheckedAt asc nulls first, p.id""")
    List<Long> findIdsToCheck(@Param("status") PaymentStatus status, @Param("createdBefore") Instant createdBefore,
                              @Param("checkedBefore") Instant checkedBefore);
}
