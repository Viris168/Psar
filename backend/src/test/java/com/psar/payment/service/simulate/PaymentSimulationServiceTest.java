package com.psar.payment.service.simulate;

import com.psar.common.exception.NotFoundException;
import com.psar.common.security.AuthenticatedUser;
import com.psar.order.service.OrderLookupService;
import com.psar.payment.config.PaymentProperties;
import com.psar.payment.repository.PaymentRepository;
import com.psar.payment.service.confirm.PaymentProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PaymentSimulationServiceTest {

    /** NotFoundException is the 404 (GlobalExceptionHandler); the switch is per environment, so no web context. */
    @Test
    @SuppressWarnings("unchecked")
    void simulate_disabled_404() {
        PaymentRepository repository = mock(PaymentRepository.class);
        PaymentProcessor processor = mock(PaymentProcessor.class);
        PaymentSimulationService service = new PaymentSimulationService(
                new PaymentProperties(null, null, null, new PaymentProperties.Simulate(false), null), repository,
                mock(OrderLookupService.class), processor, mock(ObjectProvider.class), Clock.systemUTC());
        AuthenticatedUser admin = new AuthenticatedUser(1L, UUID.randomUUID(), "admin@test.local", "ADMIN");

        assertThatThrownBy(() -> service.simulate(admin, UUID.randomUUID())).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(repository, processor);
    }
}
