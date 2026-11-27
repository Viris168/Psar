package com.psar.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.storage.*}: an S3-compatible bucket (MinIO locally, R2/S3 in production).
 *
 * @param publicUrl    base URL clients load files from; a file's URL is {@code publicUrl + "/" + key}
 * @param createBucket dev only: create the bucket (with public read) on startup if it is missing
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        String endpoint,
        String bucket,
        String accessKey,
        String secretKey,
        String region,
        String publicUrl,
        boolean createBucket
) {
    public String urlOf(String key) {
        String base = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        return base + "/" + key;
    }
}
