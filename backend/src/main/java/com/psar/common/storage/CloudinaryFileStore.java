package com.psar.common.storage;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * Images on Cloudinary. A file's key is its Cloudinary public ID ({@code {folder}/{path}}, no extension)
 * and its URL is the HTTPS delivery URL Cloudinary returns.
 */
@Component
public class CloudinaryFileStore implements FileStore {

    static final int TIMEOUT_SECONDS = 30;

    private final Cloudinary cloudinary;
    private final StorageProperties properties;

    public CloudinaryFileStore(StorageProperties properties) {
        this.properties = properties;
        // the Map constructor, so an empty URL doesn't fall back to reading CLOUDINARY_URL itself
        this.cloudinary = properties.configured()
                ? new Cloudinary(properties.cloudinaryUrl())
                : new Cloudinary(Map.of());
        this.cloudinary.config.secure = true;
        // connect + response timeout, so a hanging Cloudinary fails the request instead of blocking it
        this.cloudinary.config.timeout = TIMEOUT_SECONDS;
    }

    @Override
    public StoredFile put(String path, byte[] content, ImageType type) {
        String publicId = properties.folder() + "/" + path;
        Map<?, ?> result = call(() -> cloudinary.uploader().upload(content, ObjectUtils.asMap(
                "public_id", publicId,
                "asset_folder", publicId.substring(0, publicId.lastIndexOf('/')),
                "resource_type", "image",
                "overwrite", false,
                "return_error", true)));
        if (result.get("error") instanceof Map<?, ?> error) {
            // 400: Cloudinary could not read it as an image (right signature, broken content)
            if (Integer.valueOf(400).equals(error.get("http_code"))) {
                throw InvalidFileException.unsupportedType();
            }
            throw new IllegalStateException("Cloudinary upload failed: " + error.get("message"));
        }
        return new StoredFile((String) result.get("public_id"), (String) result.get("secure_url"));
    }

    @Override
    public void delete(String key) {
        // "not found" is a normal result, not an exception; invalidate also clears the CDN copy
        call(() -> cloudinary.uploader().destroy(key, ObjectUtils.asMap("resource_type", "image", "invalidate", true)));
    }

    private Map<?, ?> call(CloudinaryCall call) {
        if (!properties.configured()) {
            throw new IllegalStateException("File storage is not configured: set CLOUDINARY_URL");
        }
        try {
            return call.run();
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not reach Cloudinary", ex);
        }
    }

    @FunctionalInterface
    private interface CloudinaryCall {
        Map<?, ?> run() throws IOException;
    }
}
