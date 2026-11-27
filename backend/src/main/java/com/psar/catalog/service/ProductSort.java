package com.psar.catalog.service;

import org.springframework.data.domain.Sort;

import java.util.Arrays;
import java.util.Locale;

/** Sort allowlist for product lists ({@code ?sort=}). Unknown or missing values fall back to {@link #NEWEST}. */
public enum ProductSort {

    NEWEST(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))),
    PRICE_ASC(Sort.by(Sort.Order.asc("sellPrice"), Sort.Order.asc("id"))),
    PRICE_DESC(Sort.by(Sort.Order.desc("sellPrice"), Sort.Order.asc("id"))),
    NAME(Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id")));

    private final Sort sort;

    ProductSort(Sort sort) {
        this.sort = sort;
    }

    public Sort sort() {
        return sort;
    }

    /** {@code "price_asc"} -> PRICE_ASC; anything else -> NEWEST. */
    public static ProductSort parse(String value) {
        if (value == null) {
            return NEWEST;
        }
        String name = value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(s -> s.name().equals(name)).findFirst().orElse(NEWEST);
    }
}
