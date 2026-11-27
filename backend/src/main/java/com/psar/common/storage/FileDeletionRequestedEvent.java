package com.psar.common.storage;

/** Delete this object from the bucket once the current transaction has committed. */
public record FileDeletionRequestedEvent(String key) {
}
