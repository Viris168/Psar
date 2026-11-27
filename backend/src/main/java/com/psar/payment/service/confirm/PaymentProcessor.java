package com.psar.payment.service.confirm;

import com.psar.payment.entity.PaymentEvent;
import com.psar.payment.entity.PaymentOutcome;
import com.psar.payment.repository.PaymentEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way a gateway payment becomes SUCCESS (task/task5.md, payment flow): one transaction, payment row
 * locked, idempotent, every attempt recorded in payment_events.
 * <p>
 * Step 0 version: records the attempt as REJECTED and changes nothing. Agent B (Phase 5) implements it; the
 * signature stays. It may use the order module's {@code OrderLookupService} (to tell LATE), but publishes
 * {@code PaymentSucceededEvent} instead of calling the order module.
 */
@Service
@RequiredArgsConstructor
public class PaymentProcessor {

    private final PaymentEventRepository paymentEventRepository;

    @Transactional
    public PaymentOutcome confirm(PaymentConfirmation confirmation) {
        paymentEventRepository.save(PaymentEvent.builder()
                .source(confirmation.source())
                .provider(confirmation.provider())
                .providerRef(confirmation.providerRef())
                .transactionRef(confirmation.transactionRef())
                .amount(confirmation.amount())
                .currency(confirmation.currency())
                .payload(confirmation.payload())
                .signatureValid(confirmation.signatureValid())
                .outcome(PaymentOutcome.REJECTED)
                .build());
        return PaymentOutcome.REJECTED;
    }
}
