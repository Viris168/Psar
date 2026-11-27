package com.psar.payment.service.confirm;

import com.psar.order.dto.response.OrderSummary;
import com.psar.order.service.OrderLookupService;
import com.psar.payment.entity.Payment;
import com.psar.payment.entity.PaymentEvent;
import com.psar.payment.entity.PaymentEventSource;
import com.psar.payment.entity.PaymentOutcome;
import com.psar.payment.entity.PaymentStatus;
import com.psar.payment.event.LatePaymentEvent;
import com.psar.payment.event.PaymentSucceededEvent;
import com.psar.payment.repository.PaymentEventRepository;
import com.psar.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * The only way a gateway payment becomes SUCCESS (task/task5.md, payment flow): one transaction, payment row
 * locked ({@code SELECT … FOR UPDATE}: the order first, then the payment, like every cancel path), idempotent,
 * every attempt recorded in payment_events.
 * <ul>
 *   <li>bad signature / no reference / no transaction ref → REJECTED, nothing changed</li>
 *   <li>no payment with this reference → UNKNOWN_PAYMENT</li>
 *   <li>already SUCCESS or REFUNDED → DUPLICATE, nothing changed</li>
 *   <li>amount or currency differs → AMOUNT_MISMATCH, payment untouched</li>
 *   <li>order no longer PENDING_PAYMENT (cancelled / expired) → LATE: payment SUCCESS + refund_required,
 *       {@link LatePaymentEvent}; the order stays as it is</li>
 *   <li>otherwise → APPLIED: payment SUCCESS, {@link PaymentSucceededEvent} (the order module moves the order
 *       to PAID in this same transaction)</li>
 * </ul>
 * Uses the order module only through {@link OrderLookupService} (to tell LATE); never calls order services that
 * write.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentProcessor {

    private static final String AWAITING_PAYMENT = "PENDING_PAYMENT";

    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final OrderLookupService orderLookupService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Transactional
    public PaymentOutcome confirm(PaymentConfirmation confirmation) {
        if (Boolean.FALSE.equals(confirmation.signatureValid()) || confirmation.provider() == null
                || isBlank(confirmation.providerRef())) {
            return record(confirmation, null, PaymentOutcome.REJECTED);
        }
        // lock order, then payment: the same order as cancel / expiry / refund, so they never deadlock
        Optional<Long> orderId = paymentRepository.findOrderIdByProviderRef(confirmation.provider(),
                confirmation.providerRef());
        OrderSummary order = orderId.map(orderLookupService::lockForPayment).orElse(null);
        Optional<Payment> locked = paymentRepository.lockByProviderRef(confirmation.provider(),
                confirmation.providerRef());
        if (locked.isEmpty()) {
            log.warn("Payment confirmation ({}) for an unknown {} reference", confirmation.source(),
                    confirmation.provider());
            return record(confirmation, null, PaymentOutcome.UNKNOWN_PAYMENT);
        }
        Payment payment = locked.get();

        if (payment.getStatus() == PaymentStatus.SUCCESS || payment.getStatus() == PaymentStatus.REFUNDED) {
            if (confirmation.transactionRef() != null
                    && !confirmation.transactionRef().equals(payment.getTransactionRef())) {
                log.warn("Payment {} is already paid but {} reports another transaction: check for a double payment",
                        payment.getPublicId(), confirmation.source());
            }
            return record(confirmation, payment, PaymentOutcome.DUPLICATE);
        }
        if (!sameMoney(payment, confirmation)) {
            log.warn("Payment {}: amount / currency reported by {} differs from the order", payment.getPublicId(),
                    confirmation.source());
            return record(confirmation, payment, PaymentOutcome.AMOUNT_MISMATCH);
        }
        if (isBlank(confirmation.transactionRef())) {
            return record(confirmation, payment, PaymentOutcome.REJECTED);
        }
        if (transactionRefUsedElsewhere(confirmation.transactionRef(), payment.getId())) {
            log.error("Payment {}: bank transaction already paid another payment; rejected", payment.getPublicId());
            return record(confirmation, payment, PaymentOutcome.REJECTED);
        }

        boolean late = payment.getStatus() != PaymentStatus.PENDING
                || (order != null && !AWAITING_PAYMENT.equals(order.status()));
        Instant paidAt = confirmation.paidAt() != null ? confirmation.paidAt() : clock.instant();
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setTransactionRef(confirmation.transactionRef());
        payment.setPaidAt(paidAt);
        payment.setRefundRequired(late);
        if (confirmation.source() == PaymentEventSource.WEBHOOK) {
            payment.setRawWebhook(confirmation.payload());
        }
        // flush now: the UNIQUE transaction_ref fails here, inside confirm, not later at commit
        paymentRepository.saveAndFlush(payment);

        if (late) {
            log.warn("Late payment {} for order id {}: refund required", payment.getPublicId(), payment.getOrderId());
            events.publishEvent(new LatePaymentEvent(payment.getId(), payment.getOrderId()));
            return record(confirmation, payment, PaymentOutcome.LATE);
        }
        events.publishEvent(new PaymentSucceededEvent(payment.getId(), payment.getOrderId(), paidAt));
        log.info("Payment {} confirmed by {}", payment.getPublicId(), confirmation.source());
        return record(confirmation, payment, PaymentOutcome.APPLIED);
    }


    private static boolean sameMoney(Payment payment, PaymentConfirmation confirmation) {
        return confirmation.amount() != null && confirmation.amount().compareTo(payment.getAmount()) == 0
                && confirmation.currency() != null && confirmation.currency().equalsIgnoreCase(payment.getCurrency());
    }

    private boolean transactionRefUsedElsewhere(String transactionRef, Long paymentId) {
        return paymentRepository.exists((root, query, cb) -> cb.and(
                cb.equal(root.get("transactionRef"), transactionRef),
                cb.notEqual(root.get("id"), paymentId)));
    }

    private PaymentOutcome record(PaymentConfirmation confirmation, Payment payment, PaymentOutcome outcome) {
        paymentEventRepository.save(PaymentEvent.builder()
                .paymentId(payment == null ? null : payment.getId())
                .source(confirmation.source())
                .provider(confirmation.provider())
                .providerRef(confirmation.providerRef())
                .transactionRef(confirmation.transactionRef())
                .amount(confirmation.amount())
                .currency(confirmation.currency())
                .payload(confirmation.payload())
                .signatureValid(confirmation.signatureValid())
                .outcome(outcome)
                .receivedAt(clock.instant())
                .build());
        return outcome;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
