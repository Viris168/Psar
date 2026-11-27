package com.psar.order.service;

import com.psar.common.exception.NotFoundException;
import com.psar.order.dto.response.OrderSummary;
import com.psar.order.entity.Order;
import com.psar.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Order lookups for other modules, which can't use order entities. */
@Service
@RequiredArgsConstructor
public class OrderLookupService {

    private final OrderRepository orderRepository;

    /** 404 if missing. */
    @Transactional(readOnly = true)
    public OrderSummary summary(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> NotFoundException.of("Order"));
        return new OrderSummary(order.getId(), order.getPublicId(), order.getOrderNo(), order.getUserId(),
                order.getStatus().name(), order.getPaymentMethod().name(), order.getDeliveryType().name(),
                order.getTotalAmount());
    }
}
