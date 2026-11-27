package com.psar.payment.job;

import com.psar.payment.config.PaymentProperties;
import com.psar.payment.entity.Payment;
import com.psar.payment.entity.PaymentEventSource;
import com.psar.payment.entity.PaymentOutcome;
import com.psar.payment.entity.PaymentStatus;
import com.psar.payment.gateway.GatewayPayment;
import com.psar.payment.gateway.PaymentGateways;
import com.psar.payment.repository.PaymentRepository;
import com.psar.payment.service.confirm.PaymentConfirmation;
import com.psar.payment.service.confirm.PaymentProcessor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Every minute: asks the gateway ({@code PaymentGateway.checkStatus}, Bakong: by md5) about gateway payments
 * <ul>
 *   <li>PENDING, or CANCELLED with {@code expires_at} in the last {@code late-window} (30 min) — a customer
 *       may still pay an expired QR, which must be caught as a LATE payment;</li>
 *   <li>created at least {@code min-age} (1 min) ago and not checked in the last {@code interval} (1 min).</li>
 * </ul>
 * Paid → {@link PaymentProcessor#confirm} (source CHECK_JOB). Every check bumps {@code check_attempts} and
 * {@code last_checked_at}. The gateway call runs outside any transaction; then each payment is confirmed and
 * counted in its own transaction under the payment's row lock, so one failure doesn't stop the rest and a
 * webhook confirming the same payment at the same moment is never overwritten. ShedLock: one instance runs it.
 */
@Slf4j
@Component
public class PaymentCheckJob {

    /** At most this many payments per run; the oldest-checked go first, the rest wait for the next minute. */
    static final int BATCH_SIZE = 100;

    private final PaymentRepository paymentRepository;
    private final PaymentGateways gateways;
    private final PaymentProcessor processor;
    private final PaymentProperties.Check settings;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public PaymentCheckJob(PaymentRepository paymentRepository, PaymentGateways gateways, PaymentProcessor processor,
                           PaymentProperties properties, PlatformTransactionManager transactionManager, Clock clock) {
        this.paymentRepository = paymentRepository;
        this.gateways = gateways;
        this.processor = processor;
        this.settings = properties.check();
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(cron = "30 * * * * *")
    @SchedulerLock(name = "PaymentCheckJob", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void run() {
        checkPayments();
    }

    /** Returns how many payments were confirmed (APPLIED or LATE). Tests call this directly. */
    public int checkPayments() {
        Instant now = clock.instant();
        List<Payment> due = paymentRepository.findAll(dueForCheck(now),
                PageRequest.of(0, BATCH_SIZE)).getContent();
        int confirmed = 0;
        for (Payment payment : due) {
            try {
                PaymentOutcome outcome = check(payment, now);
                if (outcome == PaymentOutcome.APPLIED || outcome == PaymentOutcome.LATE) {
                    confirmed++;
                }
            } catch (RuntimeException ex) {
                log.error("Payment check failed for payment {}", payment.getPublicId(), ex);
            }
        }
        if (confirmed > 0) {
            log.info("Payment check confirmed {} payment(s)", confirmed);
        }
        return confirmed;
    }

    private PaymentOutcome check(Payment payment, Instant now) {
        Optional<GatewayPayment> paid = askGateway(payment);
        return transaction.execute(status -> {
            PaymentOutcome outcome = paid.map(p -> processor.confirm(new PaymentConfirmation(
                    PaymentEventSource.CHECK_JOB, payment.getProvider(), payment.getProviderRef(),
                    p.transactionRef(), p.amount(), p.currency(), p.paidAt(), p.raw(), null))).orElse(null);
            // locked (confirm already holds the lock, or takes it here): a concurrent webhook is never overwritten
            paymentRepository.lockByProviderRef(payment.getProvider(), payment.getProviderRef())
                    .ifPresent(locked -> {
                        locked.setLastCheckedAt(now);
                        locked.setCheckAttempts(locked.getCheckAttempts() + 1);
                    });
            return outcome;
        });
    }

    /** A gateway that throws is treated like "not paid yet". */
    private Optional<GatewayPayment> askGateway(Payment payment) {
        try {
            return gateways.forProvider(payment.getProvider()).checkStatus(payment.getProviderRef());
        } catch (RuntimeException ex) {
            log.warn("Gateway {} check failed for payment {}: {}", payment.getProvider(), payment.getPublicId(),
                    ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private Specification<Payment> dueForCheck(Instant now) {
        Instant createdBefore = now.minus(orDefault(settings == null ? null : settings.minAge()));
        Instant checkedBefore = now.minus(orDefault(settings == null ? null : settings.interval()));
        Instant expiredAfter = now.minus(settings == null || settings.lateWindow() == null
                ? Duration.ofMinutes(30) : settings.lateWindow());
        return (root, query, cb) -> {
            // never-checked first, then the longest ago (Criteria can't say NULLS FIRST)
            query.orderBy(cb.asc(cb.coalesce(root.get("lastCheckedAt"), Instant.EPOCH)), cb.asc(root.get("id")));
            return cb.and(
                    cb.isNotNull(root.get("provider")),
                    cb.isNotNull(root.get("providerRef")),
                    cb.lessThan(root.get("createdAt"), createdBefore),
                    cb.or(cb.isNull(root.get("lastCheckedAt")), cb.lessThan(root.get("lastCheckedAt"), checkedBefore)),
                    cb.or(cb.equal(root.get("status"), PaymentStatus.PENDING),
                            cb.and(cb.equal(root.get("status"), PaymentStatus.CANCELLED),
                                    cb.greaterThan(root.get("expiresAt"), expiredAfter))));
        };
    }

    private static Duration orDefault(Duration duration) {
        return duration == null ? Duration.ofMinutes(1) : duration;
    }
}
