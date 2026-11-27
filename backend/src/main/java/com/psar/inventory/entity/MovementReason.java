package com.psar.inventory.entity;

import java.util.Set;

public enum MovementReason {
    /** Purchase order received. */
    PURCHASE,
    ORDER,
    /** Stock held for an unpaid order (Phase 4). */
    RESERVE,
    /** Reserved stock given back (order expired). */
    RELEASE,
    CANCEL,
    DAMAGE,
    RETURN,
    CORRECTION,
    REWARD;

    /** Reasons a manager may pick for a manual adjustment. */
    public static final Set<MovementReason> MANUAL_ADJUSTMENT = Set.of(DAMAGE, RETURN, CORRECTION);
}
