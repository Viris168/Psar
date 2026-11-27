package com.psar.audit.service;

/** Names stored in audit_logs.entity. Login rows use {@link AuditService#AUTH}. */
public final class AuditEntity {

    public static final String USER = "USER";
    public static final String DELIVERY_COMPANY = "DELIVERY_COMPANY";
    public static final String CATEGORY = "CATEGORY";
    public static final String PRODUCT = "PRODUCT";
    public static final String PRODUCT_IMAGE = "PRODUCT_IMAGE";
    public static final String SUPPLIER = "SUPPLIER";
    public static final String PURCHASE_ORDER = "PURCHASE_ORDER";
    public static final String STOCK_ADJUSTMENT = "STOCK_ADJUSTMENT";

    private AuditEntity() {
    }
}
