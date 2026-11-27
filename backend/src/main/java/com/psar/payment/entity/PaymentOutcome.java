package com.psar.payment.entity;

/** What one confirmation attempt did (see task/task5.md, payment flow). */
public enum PaymentOutcome {
    /** Payment SUCCESS, order PAID. */
    APPLIED,
    /** Already SUCCESS: nothing changed. */
    DUPLICATE,
    /** Paid after the order was cancelled / expired: SUCCESS + refund required, order unchanged. */
    LATE,
    /** Amount or currency differs from the payment: nothing changed. */
    AMOUNT_MISMATCH,
    /** No payment with this reference. */
    UNKNOWN_PAYMENT,
    /** Bad signature, unparsable, or not processed (Step 0). */
    REJECTED
}
