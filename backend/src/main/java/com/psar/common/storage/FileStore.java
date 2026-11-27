package com.psar.common.storage;

/**
 * Where file bytes are kept. {@link StorageService} validates files and handles transactions;
 * an implementation only stores and deletes. Cloudinary in the app, an in-memory fake in tests.
 */
public interface FileStore {

    /**
     * @param path folder and name without extension, e.g. {@code products/3f2a…}
     * @return the stored file; its key is what {@link #delete} takes
     * @throws InvalidFileException 415 if the store rejects the file as not a real image
     */
    StoredFile put(String path, byte[] content, ImageType type);

    /** Deleting a missing key is a no-op. */
    void delete(String key);
}
