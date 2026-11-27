package com.psar.order.job;

import com.psar.order.entity.OrderStatus;
import com.psar.order.repository.OrderRepository;
import com.psar.order.service.orders.OrderWorkflow;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The integration tests run with scheduling (and so ShedLock) off and call {@code expireUnpaidOrders()} directly,
 * which hid that ShedLock refused to lock the scheduled method. This test calls the scheduled entry point through
 * the real ShedLock proxy, the way the scheduler does in production.
 */
@SpringJUnitConfig
class OrderExpiryJobLockTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Configuration
    @EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
    static class Config {

        @Bean
        LockProvider lockProvider() {
            LockProvider provider = mock(LockProvider.class);
            when(provider.lock(any())).thenReturn(Optional.of(mock(SimpleLock.class)));
            return provider;
        }

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        OrderExpiryJob orderExpiryJob(OrderRepository orderRepository, OrderWorkflow workflow, Clock clock) {
            return new OrderExpiryJob(orderRepository, workflow, clock);
        }
    }

    @MockitoBean
    private OrderRepository orderRepository;

    @MockitoBean
    private OrderWorkflow workflow;

    @Autowired
    private OrderExpiryJob job;

    @Autowired
    private LockProvider lockProvider;

    @Test
    void run_throughShedLockProxy_takesLockAndExpiresOrders() {
        when(orderRepository.findIdsByStatusAndExpiresAtBefore(OrderStatus.PENDING_PAYMENT, NOW))
                .thenReturn(List.of(7L));

        job.run();

        verify(lockProvider).lock(any());
        verify(workflow).expire(eq(7L));
    }
}
