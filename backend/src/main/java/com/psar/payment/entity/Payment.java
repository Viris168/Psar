package com.psar.payment.entity;

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
 * The payment of an order. KHQR payments have a {@link #provider} (the gateway) and a {@link #providerRef}
 * (Bakong: the QR's md5); cash on delivery has neither and becomes SUCCESS when the order is delivered.
 * Only {@code PaymentProcessor} marks a gateway payment SUCCESS.
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

    /** The order module's orders.id. */
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

    // ---- gateway -----------------------------------------------------------------------------------

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", length = 20)
    private PaymentProvider provider;

    /** The gateway's reference for this charge (Bakong KHQR: the QR's md5). Never shown to customers. */
    @Column(name = "provider_ref", length = 100)
    private String providerRef;

    /** The bank's transaction reference once paid; UNIQUE, so one bank transaction pays one order at most. */
    @Column(name = "transaction_ref", unique = true, length = 100)
    private String transactionRef;

    @Column(name = "qr_string", columnDefinition = "TEXT")
    private String qrString;

    @Column(name = "qr_md5", length = 64)
    private String qrMd5;

    /** Hosted checkouts (ABA PayWay, later): where to send the customer. */
    @Column(name = "redirect_url", length = 500)
    private String redirectUrl;

    @Column(name = "expires_at")
    private Instant expiresAt;

    /** Phase 4 column; the full history of confirmation attempts is in payment_events. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_webhook", columnDefinition = "jsonb")
    private Map<String, Object> rawWebhook;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Column(name = "check_attempts", nullable = false)
    private int checkAttempts;

    // ---- refunds -----------------------------------------------------------------------------------

    /** Paid after the order was cancelled: the money must be returned by hand. */
    @Column(name = "refund_required", nullable = false)
    private boolean refundRequired;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    @Column(name = "refund_reason", length = 500)
    private String refundReason;

    @Column(name = "refunded_by")
    private Long refundedBy;
}
