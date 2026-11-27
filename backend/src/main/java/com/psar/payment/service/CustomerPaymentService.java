package com.psar.payment.service;

import com.psar.common.exception.NotFoundException;
import com.psar.order.dto.response.OrderSummary;
import com.psar.order.service.OrderLookupService;
import com.psar.payment.dto.response.CustomerPaymentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/** The customer's view of their order's payment. Someone else's order (or no payment) is a 404. */
@Service
@RequiredArgsConstructor
public class CustomerPaymentService {

    private final OrderLookupService orderLookupService;
    private final PaymentService paymentService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public CustomerPaymentResponse forMyOrder(Long userId, UUID orderPublicId) {
        OrderSummary order = orderLookupService.findMine(userId, orderPublicId)
                .orElseThrow(() -> NotFoundException.of("Order"));
        return paymentService.findForOrder(order.id())
                .map(payment -> CustomerPaymentResponse.of(payment, clock.instant()))
                .orElseThrow(() -> NotFoundException.of("Payment"));
    }
}
