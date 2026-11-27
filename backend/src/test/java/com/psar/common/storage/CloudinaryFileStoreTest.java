package com.psar.common.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloudinaryFileStoreTest {

    private final CloudinaryFileStore store = new CloudinaryFileStore(new StorageProperties("", "psar-test"));

    @Test
    void put_noCloudinaryUrl_throwsNotConfigured() {
        assertThatThrownBy(() -> store.put("products/x", new byte[]{1}, ImageType.PNG))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLOUDINARY_URL");
    }

    @Test
    void delete_noCloudinaryUrl_throwsNotConfigured() {
        assertThatThrownBy(() -> store.delete("psar-test/products/x"))
                .isInstanceOf(IllegalStateException.class);
    }
}
