package com.psar.support;

import com.psar.common.storage.FileStore;
import com.psar.common.storage.ImageType;
import com.psar.common.storage.StoredFile;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Test {@link FileStore}: Cloudinary has no local emulator, so integration tests keep files in memory. */
public class InMemoryFileStore implements FileStore {

    public static final String BASE_URL = "https://files.test/";

    private final Map<String, ImageType> files = new ConcurrentHashMap<>();

    @Override
    public StoredFile put(String path, byte[] content, ImageType type) {
        files.put(path, type);
        return new StoredFile(path, urlOf(path, type));
    }

    @Override
    public void delete(String key) {
        files.remove(key);
    }

    /** The type of a stored file, empty if there is no file with this key. */
    public Optional<ImageType> typeOf(String key) {
        return Optional.ofNullable(files.get(key));
    }

    public static String urlOf(String key, ImageType type) {
        return BASE_URL + key + "." + type.extension();
    }
}
