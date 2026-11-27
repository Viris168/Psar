package com.psar.common.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Deletes files only after the DB change that dropped them has committed, so a rollback
 * never leaves a row pointing to a missing file. A failed delete only leaves an orphan file, so it is logged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageCleanupListener {

    private final StorageService storageService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onFileDeletionRequested(FileDeletionRequestedEvent event) {
        try {
            storageService.delete(event.key());
        } catch (RuntimeException ex) {
            log.warn("Could not delete stored file '{}': {}", event.key(), ex.getMessage());
        }
    }
}
