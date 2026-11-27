package com.psar.audit.service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the {@code details} map of an audit row. Unlike {@link Map#of} it keeps null values and insertion
 * order, and turns values into plain JSON ones: money as a string with its scale ({@code "1.50"}), enums by
 * name, UUIDs as strings. Never add passwords, password hashes, tokens or storage keys.
 */
public final class AuditFields {

    private final Map<String, Object> values = new LinkedHashMap<>();

    private AuditFields() {
    }

    public static AuditFields create() {
        return new AuditFields();
    }

    public AuditFields put(String field, Object value) {
        values.put(field, value);
        return this;
    }

    /** The JSON-ready map for {@link AuditService#record}. */
    public Map<String, Object> toMap() {
        Map<String, Object> json = new LinkedHashMap<>();
        values.forEach((field, value) -> json.put(field, normalize(value)));
        return json;
    }

    /** The values as given (money keeps its BigDecimal, for comparing by value). */
    Map<String, Object> raw() {
        return values;
    }

    static Object normalize(Object value) {
        return switch (value) {
            case null -> null;
            case BigDecimal decimal -> (decimal.scale() < 2 ? decimal.setScale(2) : decimal).toPlainString();
            case Enum<?> e -> e.name();
            case UUID uuid -> uuid.toString();
            case Collection<?> collection -> collection.stream().map(AuditFields::normalize).toList();
            case Map<?, ?> map -> {
                Map<Object, Object> copy = new LinkedHashMap<>();
                map.forEach((k, v) -> copy.put(k, normalize(v)));
                yield copy;
            }
            default -> value;
        };
    }
}
