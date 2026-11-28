package com.psar.order.controller;

import com.psar.common.security.AuthenticatedUser;
import com.psar.order.dto.response.CheckoutQuoteResponse;
import com.psar.order.entity.PaymentMethod;
import com.psar.order.service.quote.CheckoutQuoteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The totals my cart would cost at checkout, for an address and payment method, without placing an order. */
@Tag(name = "Checkout")
@RestController
@RequestMapping("/api/checkout")
@PreAuthorize("hasRole('CUSTOMER')")
@RequiredArgsConstructor
public class CheckoutQuoteController {

    private final CheckoutQuoteService checkoutQuoteService;

    @Operation(summary = "Quote my cart: lines, subtotal, promotions, delivery fee and total (422 if the address "
            + "can't be delivered with this payment method; a coupon that can't be used is explained, not an error)")
    @GetMapping("/quote")
    public CheckoutQuoteResponse quote(@AuthenticationPrincipal AuthenticatedUser me,
                                       @RequestParam UUID addressPublicId,
                                       @RequestParam PaymentMethod paymentMethod,
                                       @RequestParam(required = false) String couponCode) {
        return checkoutQuoteService.quote(me.userId(), addressPublicId, paymentMethod, couponCode);
    }
}
