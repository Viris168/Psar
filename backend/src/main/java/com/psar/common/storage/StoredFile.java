package com.psar.common.storage;

/**
 * A file in the bucket.
 *
 * @param key object key, e.g. {@code products/3f2a….webp}; keep it to delete the file later. Never returned to clients.
 * @param url public URL of the file
 */
public record StoredFile(String key, String url) {
}
