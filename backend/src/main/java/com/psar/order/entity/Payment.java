package com.psar.order.entity;

import com.psar.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The payment of an order. Phase 4 creates it PENDING (no QR) and cancels it with the order;
 * Phase 5 adds the KHQR code, the webhook and moves it to the payment module.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "payments")
public class Payment extends BaseEntity {

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId = UUID.randomUUID();

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", nullable = false, length = 20, updatable = false)
    private PaymentType paymentType = PaymentType.ORDER;

    @Column(name = "order_id", updatable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 20, updatable = false)
    private PaymentMethod method;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency = "USD";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(name = "transaction_ref", unique = true, length = 100)
    private String transactionRef;

    @Column(name = "qr_string", columnDefinition = "TEXT")
    private String qrString;

    @Column(name = "qr_md5", length = 64)
    private String qrMd5;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_webhook", columnDefinition = "jsonb")
    private Map<String, Object> rawWebhook;

    @Column(name = "paid_at")
    private Instant paidAt;
}
