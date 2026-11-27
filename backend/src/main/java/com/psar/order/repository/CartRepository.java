package com.psar.order.repository;

import com.psar.order.entity.Cart;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CartRepository extends JpaRepository<Cart, Long> {

    @EntityGraph(attributePaths = "items")
    Optional<Cart> findByUserId(Long userId);

    /**
     * Creates the user's empty cart unless it exists. Safe when two requests race: the loser's insert does
     * nothing instead of failing on the unique user_id.
     */
    @Modifying
    @Query(value = "INSERT INTO carts (user_id, created_at) VALUES (:userId, NOW()) ON CONFLICT (user_id) DO NOTHING",
            nativeQuery = true)
    void createIfMissing(@Param("userId") Long userId);

    /**
     * Checkout and every cart change lock the cart, so two writes to the same cart (or a change during
     * checkout) run one after the other instead of overwriting each other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cart c where c.userId = :userId")
    Optional<Cart> lockByUserId(@Param("userId") Long userId);
}
