package com.psar.payment.controller;

import com.psar.common.security.AuthenticatedUser;
import com.psar.payment.dto.response.CustomerPaymentResponse;
import com.psar.payment.service.CustomerPaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The logged-in customer's order payment. Someone else's order is a 404. */
@Tag(name = "Payments")
@RestController
@RequestMapping("/api/orders")
@PreAuthorize("hasRole('CUSTOMER')")
@RequiredArgsConstructor
public class CustomerPaymentController {

    private final CustomerPaymentService customerPaymentService;

    @Operation(summary = "My order's payment: status, method, amount, currency, expiry, paidAt and the KHQR "
            + "string to scan (only while PENDING and not expired)")
    @GetMapping("/{publicId}/payment")
    public CustomerPaymentResponse get(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID publicId) {
        return customerPaymentService.forMyOrder(me.userId(), publicId);
    }
}
