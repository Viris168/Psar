package com.psar.inventory.entity;

/** IN adds stock (quantity > 0), OUT removes it (quantity < 0), ADJUST is a manual change either way. */
public enum MovementType {
    IN, OUT, ADJUST
}
