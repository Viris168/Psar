package com.psar.payment.job;

import com.psar.payment.config.PaymentProperties;
import com.psar.payment.entity.Payment;
import com.psar.payment.entity.PaymentProvider;
import com.psar.payment.gateway.PaymentGateway;
import com.psar.payment.gateway.PaymentGateways;
import com.psar.payment.repository.PaymentRepository;
import com.psar.payment.service.confirm.PaymentProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentCheckJobUnitTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @SuppressWarnings("unchecked")
    void checkJob_bakongDown_noException() {
        PaymentRepository repository = mock(PaymentRepository.class);
        PaymentGateways gateways = mock(PaymentGateways.class);
        PaymentGateway gateway = mock(PaymentGateway.class);
        PaymentProcessor processor = mock(PaymentProcessor.class);
        Payment payment = new Payment();
        payment.setProvider(PaymentProvider.BAKONG_KHQR);
        payment.setProviderRef("md5");
        payment.setAmount(new BigDecimal("1.00"));
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(payment)));
        when(repository.lockByProviderRef(PaymentProvider.BAKONG_KHQR, "md5")).thenReturn(Optional.of(payment));
        when(gateways.forProvider(PaymentProvider.BAKONG_KHQR)).thenReturn(gateway);
        when(gateway.checkStatus(anyString())).thenThrow(new IllegalStateException("connection refused"));
        PaymentProperties properties = new PaymentProperties(null, null, null, null,
                new PaymentProperties.Check(Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(30)));
        PaymentCheckJob job = new PaymentCheckJob(repository, gateways, processor, properties,
                mock(PlatformTransactionManager.class), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatCode(job::run).doesNotThrowAnyException();

        assertThat(job.checkPayments()).isZero();
        verifyNoInteractions(processor);
        assertThat(payment.getCheckAttempts()).isEqualTo(2); // counted on both runs
        assertThat(payment.getLastCheckedAt()).isEqualTo(NOW);
    }
}
