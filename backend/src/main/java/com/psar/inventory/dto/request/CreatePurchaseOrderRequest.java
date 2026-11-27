package com.psar.inventory.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** No total here: the backend calculates it from the items (a client-sent total is ignored). */
public record CreatePurchaseOrderRequest(
        @NotNull UUID supplierPublicId,
        @Size(max = 1000) String note,
        @NotEmpty @Size(max = 200) List<@Valid @NotNull PurchaseOrderItemRequest> items
) {
}
