package com.psar.payment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A late payment was returned by hand: how (e.g. "Returned by ABA transfer, ref 123"). */
public record MarkRefundedRequest(@NotBlank @Size(max = 500) String reason) {
}
