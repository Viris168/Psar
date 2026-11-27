package com.psar.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * One row per stock change, written by StockService in the same transaction as the change.
 * Append-only (no updated_at), so it does not extend BaseEntity.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "stock_movements")
@EntityListeners(AuditingEntityListener.class)
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ID only: products belong to the catalog module. */
    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10, updatable = false)
    private MovementType type;

    /** Signed: positive adds stock, negative removes it. */
    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    /** Product quantity right after this movement. */
    @Column(name = "balance_after", nullable = false, updatable = false)
    private int balanceAfter;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 20, updatable = false)
    private MovementReason reason;

    /** Purchase order / order id that caused the movement, if any. */
    @Column(name = "reference_id", updatable = false)
    private Long referenceId;

    @Column(name = "note", updatable = false)
    private String note;

    /** User id; null for system actions. */
    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
