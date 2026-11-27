package com.psar.audit.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuditChangesTest {

    @Test
    void between_someFieldsChanged_returnsOnlyThoseWithFromAndTo() {
        AuditFields before = AuditFields.create().put("name", "Rice").put("sellPrice", new BigDecimal("1.50"))
                .put("unit", "pcs");
        AuditFields after = AuditFields.create().put("name", "Rice 5kg").put("sellPrice", new BigDecimal("1.75"))
                .put("unit", "pcs");

        assertThat(AuditChanges.between(before, after)).isEqualTo(Map.of(
                "name", Map.of("from", "Rice", "to", "Rice 5kg"),
                "sellPrice", Map.of("from", "1.50", "to", "1.75")));
    }

    @Test
    void between_moneyDiffersOnlyInScale_returnsEmpty() {
        assertThat(AuditChanges.between(
                AuditFields.create().put("baseFee", new BigDecimal("1.50")),
                AuditFields.create().put("baseFee", new BigDecimal("1.5")))).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void between_nullToValue_keepsNullAsFrom() {
        Map<String, Object> changes = AuditChanges.between(
                AuditFields.create().put("phone", null), AuditFields.create().put("phone", "012"));

        assertThat(changes).containsOnlyKeys("phone");
        assertThat((Map<String, Object>) changes.get("phone")).containsEntry("from", null).containsEntry("to", "012");
    }

    @Test
    void toMap_enumsUuidsAndMoney_becomePlainJsonValues() {
        UUID id = UUID.randomUUID();
        Map<String, Object> map = AuditFields.create().put("reason", Thread.State.NEW).put("id", id)
                .put("fee", new BigDecimal("2")).toMap();

        assertThat(map).isEqualTo(Map.of("reason", "NEW", "id", id.toString(), "fee", "2.00"));
    }
}
