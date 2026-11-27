package com.psar.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** One row per status an order reached: the customer's timeline and the staff audit trail. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "order_status_history")
public class OrderStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30, updatable = false)
    private OrderStatus status;

    /** Internal user id; null = system (expiry job). Never shown to customers. */
    @Column(name = "changed_by", updatable = false)
    private Long changedBy;

    @Column(name = "note", length = 500, updatable = false)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static OrderStatusHistory of(Long orderId, OrderStatus status, Long changedBy, String note, Instant at) {
        OrderStatusHistory row = new OrderStatusHistory();
        row.orderId = orderId;
        row.status = status;
        row.changedBy = changedBy;
        row.note = note;
        row.createdAt = at;
        return row;
    }
}
