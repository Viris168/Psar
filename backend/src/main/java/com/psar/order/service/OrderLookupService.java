package com.psar.order.service;

import com.psar.common.exception.NotFoundException;
import com.psar.order.dto.response.OrderSummary;
import com.psar.order.entity.Order;
import com.psar.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** Order lookups for other modules, which can't use order entities. */
@Service
@RequiredArgsConstructor
public class OrderLookupService {

    private final OrderRepository orderRepository;

    /** 404 if missing. */
    @Transactional(readOnly = true)
    public OrderSummary summary(Long orderId) {
        return toSummary(orderRepository.findById(orderId).orElseThrow(() -> NotFoundException.of("Order")));
    }

    /**
     * Locks the order (SELECT … FOR UPDATE) for the rest of the caller's transaction and returns it fresh.
     * Payment confirmation calls this <b>before</b> locking the payment, the same order (order, then payment)
     * that cancel, expiry and refund use, so a payment arriving as the order expires can't deadlock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OrderSummary lockForPayment(Long orderId) {
        return toSummary(orderRepository.lockById(orderId).orElseThrow(() -> NotFoundException.of("Order")));
    }

    /** The user's own order by its public id; empty if missing or someone else's. */
    @Transactional(readOnly = true)
    public Optional<OrderSummary> findMine(Long userId, UUID publicId) {
        return orderRepository.findByPublicIdAndUserId(publicId, userId).map(OrderLookupService::toSummary);
    }

    private static OrderSummary toSummary(Order order) {
        return new OrderSummary(order.getId(), order.getPublicId(), order.getOrderNo(), order.getUserId(),
                order.getStatus().name(), order.getPaymentMethod().name(), order.getDeliveryType().name(),
                order.getTotalAmount());
    }
}
