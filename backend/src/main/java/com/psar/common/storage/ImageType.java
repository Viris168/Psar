package com.psar.common.storage;

import java.util.Arrays;
import java.util.Optional;

/** Image formats we accept, recognised by their file signature (magic bytes), never by name or Content-Type. */
public enum ImageType {

    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    WEBP("webp", "image/webp");

    /** Enough bytes for every signature below. */
    static final int SIGNATURE_LENGTH = 12;

    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] RIFF = {'R', 'I', 'F', 'F'};
    private static final byte[] WEBP_MAGIC = {'W', 'E', 'B', 'P'};

    private final String extension;
    private final String contentType;

    ImageType(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    /** @param head the first bytes of the file (may be shorter than {@link #SIGNATURE_LENGTH}) */
    public static Optional<ImageType> detect(byte[] head) {
        if (startsWith(head, 0, JPEG_MAGIC)) {
            return Optional.of(JPEG);
        }
        if (startsWith(head, 0, PNG_MAGIC)) {
            return Optional.of(PNG);
        }
        // RIFF <4-byte size> WEBP
        if (startsWith(head, 0, RIFF) && startsWith(head, 8, WEBP_MAGIC)) {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] data, int offset, byte[] prefix) {
        return data.length >= offset + prefix.length
                && Arrays.equals(data, offset, offset + prefix.length, prefix, 0, prefix.length);
    }
}
