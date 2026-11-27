package com.psar.order.repository;

import com.psar.order.entity.Order;
import com.psar.order.entity.OrderStatus;
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

public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {

    /** Customer lookups: always scoped to the owner (another customer's order is "not found"). */
    Optional<Order> findByPublicIdAndUserId(UUID publicId, Long userId);

    Optional<Order> findByPublicId(UUID publicId);

    /** SELECT … FOR UPDATE: every status change locks the order first. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.publicId = :publicId")
    Optional<Order> lockByPublicId(@Param("publicId") UUID publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> lockById(@Param("id") Long id);

    /** Ids only: the expiry job locks and re-checks each order in its own transaction. */
    @Query("select o.id from Order o where o.status = :status and o.expiresAt < :now order by o.expiresAt")
    List<Long> findIdsByStatusAndExpiresAtBefore(@Param("status") OrderStatus status, @Param("now") Instant now);
}
