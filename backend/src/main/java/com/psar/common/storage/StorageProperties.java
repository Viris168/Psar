package com.psar.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.storage.*}: files are kept on Cloudinary.
 *
 * @param cloudinaryUrl {@code cloudinary://<api-key>:<api-secret>@<cloud-name>}; empty means uploads fail
 * @param folder        root folder for every file, e.g. {@code psar-dev} or {@code psar}
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        String cloudinaryUrl,
        String folder
) {
    public boolean configured() {
        return cloudinaryUrl != null && !cloudinaryUrl.isBlank();
    }
}
