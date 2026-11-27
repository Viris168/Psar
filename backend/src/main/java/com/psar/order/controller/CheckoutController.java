package com.psar.order.controller;

import com.psar.common.security.AuthenticatedUser;
import com.psar.order.dto.request.CheckoutRequest;
import com.psar.order.dto.response.CheckoutResponse;
import com.psar.order.service.checkout.CheckoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Places an order from my cart. */
@Tag(name = "Checkout")
@RestController
@RequestMapping("/api/checkout")
@PreAuthorize("hasRole('CUSTOMER')")
@RequiredArgsConstructor
public class CheckoutController {

    private final CheckoutService checkoutService;

    @Operation(summary = "Check out my cart: reserves stock and creates the order in one transaction "
            + "(KHQR → PENDING_PAYMENT, pay within 15 minutes; COD → CONFIRMED). "
            + "422 empty cart / unavailable product / delivery rule, 409 INSUFFICIENT_STOCK, 404 address")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CheckoutResponse checkout(@AuthenticationPrincipal AuthenticatedUser me,
                                     @Valid @RequestBody CheckoutRequest request) {
        return checkoutService.checkout(me.userId(), request);
    }
}
