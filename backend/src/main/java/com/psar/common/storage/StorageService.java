package com.psar.common.storage;

import com.psar.common.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.UUID;

/**
 * Files in the S3-compatible bucket (product images now; avatars, delivery logos and AI documents later).
 * Keys are {@code {folder}/{uuid}.{ext}}: the user's file name is never used.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {

    public static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

    private final S3Client s3;
    private final StorageProperties properties;
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
        ImageType type = ImageType.detect(head(file)).orElseThrow(InvalidFileException::unsupportedType);

        String key = folder + "/" + UUID.randomUUID() + "." + type.extension();
        try (InputStream in = file.getInputStream()) {
            s3.putObject(b -> b.bucket(properties.bucket()).key(key).contentType(type.contentType()),
                    RequestBody.fromInputStream(in, file.getSize()));
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read uploaded file", ex);
        }
        deleteIfRolledBack(key);
        return new StoredFile(key, properties.urlOf(key));
    }

    /** Deletes now. Inside a transaction, prefer {@link #deleteAfterCommit}. Deleting a missing key is a no-op. */
    public void delete(String key) {
        s3.deleteObject(b -> b.bucket(properties.bucket()).key(key));
    }

    /** Deletes the file after the current transaction commits (immediately if there is none). */
    public void deleteAfterCommit(String key) {
        events.publishEvent(new FileDeletionRequestedEvent(key));
    }

    private static byte[] head(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(ImageType.SIGNATURE_LENGTH);
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
