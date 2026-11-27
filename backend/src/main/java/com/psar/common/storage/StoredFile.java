package com.psar.common.storage;

/**
 * A stored file.
 *
 * @param key storage key (the Cloudinary public ID, e.g. {@code psar/products/3f2a…}); keep it to delete the
 *            file later. Never returned to clients.
 * @param url public HTTPS URL of the file
 */
public record StoredFile(String key, String url) {
}
