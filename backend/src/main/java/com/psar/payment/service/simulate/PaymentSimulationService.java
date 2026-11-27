package com.psar.payment.service.simulate;

import com.psar.common.exception.BusinessRuleException;
import com.psar.common.exception.NotFoundException;
import com.psar.common.security.AuthenticatedUser;
import com.psar.order.service.OrderLookupService;
import com.psar.payment.config.PaymentProperties;
import com.psar.payment.dto.response.SimulatedPaymentResponse;
import com.psar.payment.entity.Payment;
import com.psar.payment.entity.PaymentEventSource;
import com.psar.payment.entity.PaymentOutcome;
import com.psar.payment.gateway.GatewayPayment;
import com.psar.payment.repository.PaymentRepository;
import com.psar.payment.service.bakong.FakeBakongClient;
import com.psar.payment.service.confirm.PaymentConfirmation;
import com.psar.payment.service.confirm.PaymentProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Dev / demo only ({@code app.payment.simulate.enabled=true}): "the customer paid". Marks the QR paid at the
 * fake Bakong and confirms it straight away, exactly as the check job would. Disabled → 404, as if the endpoint
 * didn't exist. Customers may only simulate their own order (someone else's → 404); admins any.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentSimulationService {

    private final PaymentProperties properties;
    private final PaymentRepository paymentRepository;
    private final OrderLookupService orderLookupService;
    private final PaymentProcessor processor;
    private final ObjectProvider<FakeBakongClient> fakeBakong;
    private final Clock clock;

    public SimulatedPaymentResponse simulate(AuthenticatedUser me, UUID paymentPublicId) {
        if (properties.simulate() == null || !properties.simulate().enabled()) {
            throw new NotFoundException("Not found");
        }
        Payment payment = paymentRepository.findByPublicId(paymentPublicId)
                .filter(p -> me.hasRole("ADMIN") || ownedBy(p, me.userId()))
                .orElseThrow(() -> NotFoundException.of("Payment"));
        if (payment.getProvider() == null || payment.getProviderRef() == null) {
            throw new BusinessRuleException("Only QR payments can be simulated");
        }

        GatewayPayment paid = paidAtBakong(payment);
        PaymentOutcome outcome = processor.confirm(new PaymentConfirmation(PaymentEventSource.SIMULATED,
                payment.getProvider(), payment.getProviderRef(), paid.transactionRef(), paid.amount(),
                paid.currency(), paid.paidAt(), paid.raw(), null));
        log.info("Simulated payment {} by user {}: {}", payment.getPublicId(), me.publicId(), outcome);
        String status = paymentRepository.findById(payment.getId()).map(p -> p.getStatus().name()).orElseThrow();
        return new SimulatedPaymentResponse(outcome.name(), status);
    }

    private boolean ownedBy(Payment payment, Long userId) {
        return payment.getOrderId() != null
                && userId.equals(orderLookupService.summary(payment.getOrderId()).userId());
    }

    /** The fake Bakong in dev; a made-up transaction when a demo runs against the real one. */
    private GatewayPayment paidAtBakong(Payment payment) {
        FakeBakongClient fake = fakeBakong.getIfAvailable();
        if (fake != null) {
            if (fake.findPaid(payment.getProviderRef()).isEmpty()) {
                fake.markPaid(payment.getProviderRef(), payment.getAmount(), payment.getCurrency());
            }
            return fake.findPaid(payment.getProviderRef()).orElseThrow();
        }
        String ref = "SIM-" + UUID.randomUUID();
        return new GatewayPayment(ref, payment.getAmount(), payment.getCurrency(), clock.instant(),
                Map.of("simulated", true, "hash", ref));
    }
}
