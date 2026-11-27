package com.psar.order.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** PROVINCE orders: the courier company's tracking number. */
public record TrackingNumberRequest(@NotBlank @Size(max = 100) String trackingNo) {
}
