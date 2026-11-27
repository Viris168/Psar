package com.psar.payment.repository;

import com.psar.payment.entity.PaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, Long> {

    List<PaymentEvent> findAllByPaymentIdOrderByIdAsc(Long paymentId);

    List<PaymentEvent> findAllByProviderRefOrderByIdAsc(String providerRef);
}
