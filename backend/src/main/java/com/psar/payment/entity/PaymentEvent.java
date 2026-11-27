package com.psar.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** One confirmation attempt and its outcome. Append-only (no setters, never updated). */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Immutable
@Entity
@Table(name = "payment_events")
public class PaymentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null when no payment matched (UNKNOWN_PAYMENT, REJECTED). */
    @Column(name = "payment_id", updatable = false)
    private Long paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20, updatable = false)
    private PaymentEventSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", length = 20, updatable = false)
    private PaymentProvider provider;

    @Column(name = "transaction_ref", length = 100, updatable = false)
    private String transactionRef;

    @Column(name = "provider_ref", length = 100, updatable = false)
    private String providerRef;

    @Column(name = "amount", precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, updatable = false)
    private String currency;

    /** As received (admins only; never shown to customers). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> payload;

    @Column(name = "signature_valid", updatable = false)
    private Boolean signatureValid;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20, updatable = false)
    private PaymentOutcome outcome;

    @Builder.Default
    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt = Instant.now();
}
