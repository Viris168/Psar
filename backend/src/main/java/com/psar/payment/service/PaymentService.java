package com.psar.payment.service;

import com.psar.payment.dto.response.PaymentView;
import com.psar.payment.entity.Payment;
import com.psar.payment.entity.PaymentMethod;
import com.psar.payment.entity.PaymentStatus;
import com.psar.payment.gateway.ChargeRequest;
import com.psar.payment.gateway.GatewayCharge;
import com.psar.payment.gateway.PaymentGateway;
import com.psar.payment.gateway.PaymentGateways;
import com.psar.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * Payments for the order module. Must not depend on order services (the order module depends on this);
 * payment → order goes through {@code PaymentSucceededEvent}.
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final String CURRENCY = "USD";

    private final PaymentRepository paymentRepository;
    private final PaymentGateways gateways;

    /**
     * Checkout: the order's payment, PENDING. Gateway methods (KHQR) get their charge here — generated locally,
     * no network call inside the checkout transaction. Cash on delivery has no gateway.
     *
     * @param method the order's payment method name ("KHQR", "CASH_ON_DELIVERY")
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentView createForOrder(Long orderId, String orderNo, String method, BigDecimal amount,
                                      Instant expiresAt) {
        Payment payment = new Payment();
        payment.setOrderId(orderId);
        payment.setMethod(PaymentMethod.valueOf(method));
        payment.setAmount(amount);
        payment.setCurrency(CURRENCY);
        payment.setExpiresAt(expiresAt);
        Optional<PaymentGateway> gateway = gateways.forMethod(method);
        if (gateway.isPresent()) {
            GatewayCharge charge = gateway.get().createCharge(new ChargeRequest(orderNo, amount, CURRENCY, expiresAt));
            payment.setProvider(gateway.get().provider());
            payment.setProviderRef(charge.providerRef());
            payment.setQrString(charge.qrString());
            payment.setQrMd5(charge.qrString() == null ? null : charge.providerRef());
            payment.setRedirectUrl(charge.redirectUrl());
        }
        return toView(paymentRepository.save(payment));
    }

    /** Cancel / expiry: a still-PENDING payment becomes CANCELLED. Paid ones stay for the refund flow. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelPending(Long orderId) {
        paymentRepository.lockByOrderId(orderId)
                .filter(payment -> payment.getStatus() == PaymentStatus.PENDING)
                .ifPresent(payment -> payment.setStatus(PaymentStatus.CANCELLED));
    }

    @Transactional(readOnly = true)
    public Optional<PaymentView> findForOrder(Long orderId) {
        return paymentRepository.findByOrderId(orderId).map(PaymentService::toView);
    }

    static PaymentView toView(Payment payment) {
        return new PaymentView(payment.getPublicId(), payment.getMethod().name(), payment.getStatus().name(),
                payment.getAmount(), payment.getCurrency(), payment.getQrString(), payment.getRedirectUrl(),
                payment.getExpiresAt(), payment.getPaidAt(), payment.isRefundRequired());
    }
}
