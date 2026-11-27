package com.psar.order.dto.request;

import jakarta.validation.constraints.Size;

/** Customer cancel: the reason is optional. */
public record CancelOrderRequest(@Size(max = 300) String reason) {
}
