package com.psar.order.controller;

import com.psar.common.security.AuthenticatedUser;
import com.psar.order.dto.request.AddCartItemRequest;
import com.psar.order.dto.request.UpdateCartItemRequest;
import com.psar.order.dto.response.CartResponse;
import com.psar.order.service.cart.CartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The customer's own cart, always at current prices. */
@Tag(name = "Cart")
@RestController
@RequestMapping("/api/cart")
@PreAuthorize("hasRole('CUSTOMER')")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @Operation(summary = "Get my cart (current prices, line totals, subtotal, availability per line)")
    @GetMapping
    public CartResponse get(@AuthenticationPrincipal AuthenticatedUser me) {
        return cartService.get(me.userId());
    }

    @Operation(summary = "Add a product (adds up with an existing line; max 99 per line, 50 lines)")
    @PostMapping("/items")
    public CartResponse add(@AuthenticationPrincipal AuthenticatedUser me,
                            @Valid @RequestBody AddCartItemRequest request) {
        return cartService.add(me.userId(), request);
    }

    @Operation(summary = "Set the quantity of a line")
    @PutMapping("/items/{productPublicId}")
    public CartResponse setQuantity(@AuthenticationPrincipal AuthenticatedUser me,
                                    @PathVariable UUID productPublicId,
                                    @Valid @RequestBody UpdateCartItemRequest request) {
        return cartService.setQuantity(me.userId(), productPublicId, request);
    }

    @Operation(summary = "Remove a line")
    @DeleteMapping("/items/{productPublicId}")
    public CartResponse remove(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable UUID productPublicId) {
        return cartService.remove(me.userId(), productPublicId);
    }

    @Operation(summary = "Empty my cart")
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@AuthenticationPrincipal AuthenticatedUser me) {
        cartService.clear(me.userId());
    }
}
