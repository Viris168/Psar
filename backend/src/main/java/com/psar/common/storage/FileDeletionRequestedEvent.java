package com.psar.common.storage;

/** Delete this stored file once the current transaction has committed. */
public record FileDeletionRequestedEvent(String key) {
}
