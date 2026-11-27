package com.psar.inventory.entity;

/** PENDING -> RECEIVED (stock added) or PENDING -> CANCELLED. Both end states are final. */
public enum PurchaseOrderStatus {
    PENDING, RECEIVED, CANCELLED
}
