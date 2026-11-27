package com.psar.payment.controller;

import com.psar.common.security.AuthenticatedUser;
import com.psar.payment.dto.response.SimulatedPaymentResponse;
import com.psar.payment.service.simulate.PaymentSimulationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Dev / demo only: pretend the customer paid the QR. 404 unless {@code app.payment.simulate.enabled=true}. */
@Tag(name = "Payments")
@RestController
@RequestMapping("/api/payments")
@PreAuthorize("hasAnyRole('ADMIN', 'CUSTOMER')")
@RequiredArgsConstructor
public class PaymentSimulationController {

    private final PaymentSimulationService simulationService;

    @Operation(summary = "Simulate paying a KHQR payment (dev / demo only; own order, or ADMIN)")
    @PostMapping("/{publicId}/simulate")
    public SimulatedPaymentResponse simulate(@AuthenticationPrincipal AuthenticatedUser me,
                                             @PathVariable UUID publicId) {
        return simulationService.simulate(me, publicId);
    }
}
