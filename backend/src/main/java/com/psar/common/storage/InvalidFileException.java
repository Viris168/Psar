package com.psar.common.storage;

import com.psar.common.exception.ApiException;
import com.psar.common.exception.ErrorCode;

/** A rejected upload: 415 for a file type we don't accept, 413 for a file that is too large. */
public class InvalidFileException extends ApiException {

    private InvalidFileException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public static InvalidFileException unsupportedType() {
        return new InvalidFileException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Only JPEG, PNG and WebP images are allowed");
    }

    public static InvalidFileException tooLarge(long maxBytes) {
        return new InvalidFileException(ErrorCode.PAYLOAD_TOO_LARGE,
                "File is larger than " + (maxBytes / (1024 * 1024)) + " MB");
    }
}
