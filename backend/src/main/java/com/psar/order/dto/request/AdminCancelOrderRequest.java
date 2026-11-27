package com.psar.order.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Staff cancel: the reason is required and shown to the customer. */
public record AdminCancelOrderRequest(@NotBlank @Size(max = 300) String reason) {
}
