package com.psar.payment.service.admin;

import com.psar.audit.service.AuditEntity;
import com.psar.audit.service.AuditFields;
import com.psar.audit.service.AuditService;
import com.psar.common.exception.ConflictException;
import com.psar.common.exception.ErrorCode;
import com.psar.common.exception.NotFoundException;
import com.psar.payment.entity.Payment;
import com.psar.payment.entity.PaymentMethod;
import com.psar.payment.entity.PaymentStatus;
import com.psar.payment.repository.PaymentRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Payment changes that are not gateway confirmations: cash collected on delivery, refunds of paid orders
 * (called by the order module's {@code RefundService}, inside its transaction) and late payments refunded by
 * hand. Every change locks the payment row first. No money moves here: the KHQR sandbox can't refund, so a
 * refund is recorded and the money is returned by hand.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentSettlementService {

    private final PaymentRepository paymentRepository;
    private final AuditService auditService;
    private final Clock clock;
    private final EntityManager entityManager;

    /** A COD order was delivered: its PENDING payment becomes SUCCESS ("cash collected"). Anything else: no-op. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void collectCash(Long orderId) {
        paymentRepository.lockByOrderId(orderId)
                .filter(payment -> payment.getMethod() == PaymentMethod.CASH_ON_DELIVERY)
                .filter(payment -> payment.getStatus() == PaymentStatus.PENDING)
                .ifPresent(payment -> {
                    payment.setStatus(PaymentStatus.SUCCESS);
                    payment.setPaidAt(clock.instant());
                    log.info("Cash collected for order {}", orderId);
                });
    }

    /**
     * The order is being refunded: its SUCCESS payment becomes REFUNDED (else 409 INVALID_STATUS, so a second
     * refund fails). Runs in the order module's refund transaction, which writes the audit row (with the
     * order's side of the refund).
     *
     * @return the payment's public id
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID refundForOrder(Long orderId, String reason, Long actorId) {
        Payment payment = paymentRepository.lockByOrderId(orderId).orElseThrow(() -> NotFoundException.of("Payment"));
        if (payment.getStatus() != PaymentStatus.SUCCESS) {
            throw new ConflictException(ErrorCode.INVALID_STATUS,
                    "Only a paid payment can be refunded (this one is %s)".formatted(payment.getStatus()));
        }
        markRefunded(payment, reason, actorId);
        return payment.getPublicId();
    }

    /**
     * A late payment ({@code refund_required}, SUCCESS) was returned to the customer by hand → REFUNDED.
     * Not refund-required or not SUCCESS (e.g. already refunded) → 409. {@code refund_required} stays true so
     * the payment still reads as "paid late".
     */
    @Transactional
    public void markRefundedByHand(UUID publicId, String reason, Long actorId) {
        Payment payment = paymentRepository.findByPublicId(publicId)
                .orElseThrow(() -> NotFoundException.of("Payment"));
        entityManager.refresh(payment, LockModeType.PESSIMISTIC_WRITE); // SELECT … FOR UPDATE, fresh state
        if (!payment.isRefundRequired() || payment.getStatus() != PaymentStatus.SUCCESS) {
            throw new ConflictException(ErrorCode.INVALID_STATUS,
                    "Only a late payment waiting for its refund can be marked refunded (this one is %s%s)"
                            .formatted(payment.getStatus(), payment.isRefundRequired() ? "" : ", no refund required"));
        }
        PaymentStatus before = payment.getStatus();
        markRefunded(payment, reason, actorId);
        auditService.recordChanges(AuditEntity.PAYMENT, publicId.toString(),
                AuditFields.create().put("status", before).put("refundReason", null),
                AuditFields.create().put("status", PaymentStatus.REFUNDED)
                        .put("refundReason", payment.getRefundReason()));
    }

    private void markRefunded(Payment payment, String reason, Long actorId) {
        payment.setStatus(PaymentStatus.REFUNDED);
        payment.setRefundedAt(clock.instant());
        payment.setRefundedBy(actorId);
        payment.setRefundReason(reason.trim());
        paymentRepository.save(payment);
    }
}
