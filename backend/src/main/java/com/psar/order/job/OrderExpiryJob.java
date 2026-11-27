package com.psar.order.job;

import com.psar.order.entity.OrderStatus;
import com.psar.order.repository.OrderRepository;
import com.psar.order.service.orders.OrderWorkflow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * Every minute: KHQR orders still PENDING_PAYMENT after {@code expires_at} → CANCELLED ("Not paid in time"),
 * stock released, payment cancelled. Finds the ids, then each order runs in its own transaction and is
 * re-checked under its row lock, so a payment arriving at the same moment wins and one failure doesn't stop
 * the rest. ShedLock: only one instance runs it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderExpiryJob {

    private final OrderRepository orderRepository;
    private final OrderWorkflow workflow;
    private final Clock clock;

    /** Returns how many orders were cancelled (tests call this directly; scheduling is off there). */
    @Scheduled(cron = "0 * * * * *")
    @SchedulerLock(name = "OrderExpiryJob", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public int expireUnpaidOrders() {
        List<Long> ids = orderRepository.findIdsByStatusAndExpiresAtBefore(OrderStatus.PENDING_PAYMENT,
                clock.instant());
        int cancelled = 0;
        for (Long id : ids) {
            try {
                if (workflow.expire(id)) {
                    cancelled++;
                }
            } catch (RuntimeException ex) {
                log.error("Could not expire order {}", id, ex);
            }
        }
        if (cancelled > 0) {
            log.info("Expiry job cancelled {} unpaid order(s)", cancelled);
        }
        return cancelled;
    }
}
