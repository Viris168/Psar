package com.psar.payment.entity;

public enum PaymentEventSource {
    /** PaymentCheckJob asked the gateway. */
    CHECK_JOB,
    /** The gateway called our webhook. */
    WEBHOOK,
    /** Dev / demo only: "simulate payment". */
    SIMULATED
}
