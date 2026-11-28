package com.psar.promotion.dto;

import com.psar.promotion.dto.response.PromotionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PromotionStatusTest {

    private static final Instant START = Instant.parse("2026-12-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-12-31T00:00:00Z");

    @Test
    void of_boundaries_startInclusiveEndExclusive() {
        assertThat(PromotionStatus.of(true, START, END, START.minusSeconds(1))).isEqualTo(PromotionStatus.SCHEDULED);
        assertThat(PromotionStatus.of(true, START, END, START)).isEqualTo(PromotionStatus.ACTIVE);
        assertThat(PromotionStatus.of(true, START, END, END.minusSeconds(1))).isEqualTo(PromotionStatus.ACTIVE);
        assertThat(PromotionStatus.of(true, START, END, END)).isEqualTo(PromotionStatus.ENDED);
    }

    @Test
    void of_inactive_inactiveWhateverTheDates() {
        assertThat(PromotionStatus.of(false, START, END, START.minusSeconds(1))).isEqualTo(PromotionStatus.INACTIVE);
        assertThat(PromotionStatus.of(false, START, END, START)).isEqualTo(PromotionStatus.INACTIVE);
        assertThat(PromotionStatus.of(false, START, END, END)).isEqualTo(PromotionStatus.INACTIVE);
    }
}
