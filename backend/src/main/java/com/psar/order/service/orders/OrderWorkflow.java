package com.psar.order.service.orders;

import com.psar.common.exception.BusinessRuleException;
import com.psar.common.exception.ConflictException;
import com.psar.common.exception.ErrorCode;
import com.psar.common.exception.NotFoundException;
import com.psar.inventory.service.StockService;
import com.psar.order.entity.DeliveryType;
import com.psar.order.entity.Order;
import com.psar.order.entity.OrderItem;
import com.psar.order.entity.OrderStatus;
import com.psar.order.entity.OrderStatusHistory;
import com.psar.order.entity.PaymentStatus;
import com.psar.order.event.OrderStatusChangedEvent;
import com.psar.order.repository.OrderRepository;
import com.psar.order.repository.OrderStatusHistoryRepository;
import com.psar.order.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The one place an order's status changes after checkout (customer cancel, staff, expiry job; Phase 5 payments
 * later). Callers lock the order row first ({@link #lock}); {@link #move} then checks
 * {@link OrderStatus#canMoveTo}, writes the history row and publishes {@link OrderStatusChangedEvent}, and on
 * CANCELLED releases every item's stock and cancels the pending payment.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderWorkflow {

    static final String EXPIRED_NOTE = "Not paid in time";

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final PaymentRepository paymentRepository;
    private final StockService stockService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /** SELECT … FOR UPDATE by public id; 404 if missing. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Order lock(UUID publicId) {
        return orderRepository.lockByPublicId(publicId).orElseThrow(() -> NotFoundException.of("Order"));
    }

    /**
     * Moves a locked order to {@code next}. Arrow not allowed (incl. cancelling twice) → 409 INVALID_STATUS;
     * OUT_FOR_DELIVERY without a driver or HANDED_TO_COURIER without a tracking number → 422.
     *
     * @param actorId internal user id, null = system (expiry job)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void move(Order order, OrderStatus next, Long actorId, String note) {
        OrderStatus from = order.getStatus();
        if (!from.canMoveTo(next, order.getDeliveryType())) {
            throw invalidStatus(order, next);
        }
        if (next == OrderStatus.OUT_FOR_DELIVERY && order.getAssignedStaffId() == null) {
            throw new BusinessRuleException("Assign a driver before sending the order out for delivery");
        }
        if (next == OrderStatus.HANDED_TO_COURIER && !StringUtils.hasText(order.getTrackingNo())) {
            throw new BusinessRuleException("Enter the courier's tracking number before handing the order over");
        }
        String cleanNote = StringUtils.hasText(note) ? note.trim() : null;
        Instant now = clock.instant();

        order.setStatus(next);
        if (next == OrderStatus.CANCELLED) {
            releaseStockAndPayment(order, actorId);
        }
        orderRepository.save(order);
        historyRepository.save(OrderStatusHistory.of(order.getId(), next, actorId, cleanNote, now));
        events.publishEvent(new OrderStatusChangedEvent(order.getId(), from.name(), next.name(), cleanNote));
    }

    /**
     * Expiry job, one order per transaction: locks it and re-checks under the lock, so an order paid or
     * cancelled in the meantime is left alone. Returns whether it was cancelled.
     */
    @Transactional
    public boolean expire(Long orderId) {
        Order order = orderRepository.lockById(orderId).orElse(null);
        Instant now = clock.instant();
        if (order == null || order.getStatus() != OrderStatus.PENDING_PAYMENT
                || order.getExpiresAt() == null || !order.getExpiresAt().isBefore(now)) {
            return false;
        }
        move(order, OrderStatus.CANCELLED, null, EXPIRED_NOTE);
        log.info("Order {} cancelled: not paid by {}", order.getOrderNo(), order.getExpiresAt());
        return true;
    }

    /** Driver / tracking number can't change once the order is finished. */
    void requireOpen(Order order) {
        OrderStatus status = order.getStatus();
        if (status == OrderStatus.DELIVERED || status == OrderStatus.CANCELLED || status == OrderStatus.REFUNDED) {
            throw new ConflictException(ErrorCode.INVALID_STATUS,
                    "Order %s is %s and can no longer change".formatted(order.getOrderNo(), status));
        }
    }

    void requireDeliveryType(Order order, DeliveryType type, String message) {
        if (order.getDeliveryType() != type) {
            throw new BusinessRuleException(message);
        }
    }

    static ConflictException invalidStatus(Order order, OrderStatus next) {
        return new ConflictException(ErrorCode.INVALID_STATUS, "Order %s can't move from %s to %s"
                .formatted(order.getOrderNo(), order.getStatus(), next));
    }

    /**
     * Stock back (IN, RELEASE, reference = the order; StockService sorts by product id) and the payment
     * CANCELLED if it is still pending. A paid (SUCCESS) payment stays for the refund flow (Phase 5).
     */
    private void releaseStockAndPayment(Order order, Long actorId) {
        Map<Long, Integer> quantities = order.getItems().stream()
                .collect(Collectors.toMap(OrderItem::getProductId, OrderItem::stockQuantity, Integer::sum));
        if (!quantities.isEmpty()) {
            stockService.release(quantities, order.getId(), actorId);
        }
        paymentRepository.findByOrderId(order.getId())
                .filter(payment -> payment.getStatus() == PaymentStatus.PENDING)
                .ifPresent(payment -> {
                    payment.setStatus(PaymentStatus.CANCELLED);
                    paymentRepository.save(payment);
                });
    }
}
