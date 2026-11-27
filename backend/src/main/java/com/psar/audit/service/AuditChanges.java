package com.psar.audit.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** The UPDATE {@code details}: only the fields that changed, as {@code {"field": {"from": …, "to": …}}}. */
public final class AuditChanges {

    private AuditChanges() {
    }

    /** Empty when nothing changed. Money compares by value, so {@code 1.5} and {@code 1.50} are equal. */
    public static Map<String, Object> between(AuditFields beforeFields, AuditFields afterFields) {
        Map<String, Object> before = beforeFields.raw();
        Map<String, Object> after = afterFields.raw();
        Set<String> fields = new LinkedHashSet<>(before.keySet());
        fields.addAll(after.keySet());
        Map<String, Object> changes = new LinkedHashMap<>();
        for (String field : fields) {
            Object from = before.get(field);
            Object to = after.get(field);
            if (!same(from, to)) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("from", AuditFields.normalize(from));
                change.put("to", AuditFields.normalize(to));
                changes.put(field, change);
            }
        }
        return changes;
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        return Objects.equals(AuditFields.normalize(a), AuditFields.normalize(b));
    }
}
