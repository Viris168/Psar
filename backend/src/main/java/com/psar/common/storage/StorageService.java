package com.psar.common.storage;

import com.psar.common.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

/**
 * Stored files (product images now; avatars, delivery logos and AI documents later): checks uploads,
 * then hands the bytes to the {@link FileStore}. Files are named {@code {folder}/{uuid}}: the user's file name
 * is never used.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {

    public static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

    private final FileStore fileStore;
    private final ApplicationEventPublisher events;

    /**
     * Uploads a JPEG/PNG/WebP image (checked by its signature) of at most 5 MB.
     * Called inside a transaction, the file is removed again if that transaction rolls back.
     *
     * @throws InvalidFileException 415 for another file type, 413 if too large
     */
    public StoredFile upload(String folder, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("File is required");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw InvalidFileException.tooLarge(MAX_IMAGE_BYTES);
        }
        byte[] content = bytes(file);
        ImageType type = ImageType.detect(content).orElseThrow(InvalidFileException::unsupportedType);

        StoredFile stored = fileStore.put(folder + "/" + UUID.randomUUID(), content, type);
        deleteIfRolledBack(stored.key());
        return stored;
    }

    /** Deletes now. Inside a transaction, prefer {@link #deleteAfterCommit}. Deleting a missing key is a no-op. */
    public void delete(String key) {
        fileStore.delete(key);
    }

    /** Deletes the file after the current transaction commits (immediately if there is none). */
    public void deleteAfterCommit(String key) {
        events.publishEvent(new FileDeletionRequestedEvent(key));
    }

    /** At most {@link #MAX_IMAGE_BYTES}, checked before this is called. */
    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read uploaded file", ex);
        }
    }

    private void deleteIfRolledBack(String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    try {
                        delete(key);
                    } catch (RuntimeException ex) {
                        log.warn("Could not delete file '{}' after rollback: {}", key, ex.getMessage());
                    }
                }
            }
        });
    }
}
