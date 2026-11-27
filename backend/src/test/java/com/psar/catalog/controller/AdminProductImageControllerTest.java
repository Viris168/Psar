package com.psar.catalog.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.psar.catalog.entity.Category;
import com.psar.catalog.entity.Product;
import com.psar.catalog.entity.ProductImage;
import com.psar.catalog.repository.ProductImageRepository;
import com.psar.common.storage.ImageType;
import com.psar.support.CatalogFixtures;
import com.psar.support.InMemoryFileStore;
import com.psar.support.IntegrationTest;
import com.psar.support.TestAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminProductImageControllerTest extends IntegrationTest {

    private static final RequestPostProcessor MANAGER = TestAuth.asRole(1L, "MANAGER");
    private static final RequestPostProcessor STAFF = TestAuth.asRole(1L, "STAFF");

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};

    @Autowired
    private CatalogFixtures fixtures;
    @Autowired
    private ProductImageRepository imageRepository;
    @Autowired
    private InMemoryFileStore files;

    private Product product;

    @BeforeEach
    void setUp() {
        Category category = fixtures.category("Images");
        product = fixtures.product(category, "Photo product", "2.50", 0, 10);
    }

    @Test
    void uploadImage_firstImage_becomesMainAndFileIsStored() throws Exception {
        JsonNode body = read(upload(png("cover.png")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.main").value(true))
                .andExpect(jsonPath("$.sortOrder").value(0)));

        ProductImage saved = image(body);
        assertThat(saved.getStorageKey()).matches("products/[0-9a-f-]{36}");
        assertThat(body.get("imageUrl").asText()).isEqualTo(InMemoryFileStore.urlOf(saved.getStorageKey(), ImageType.PNG));
        assertThat(body.has("storageKey")).isFalse();
        assertThat(files.typeOf(saved.getStorageKey())).contains(ImageType.PNG);

        // second image is not main and goes after the first
        upload(jpeg("second.jpg")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.main").value(false))
                .andExpect(jsonPath("$.sortOrder").value(1));
    }

    @Test
    void uploadImage_textAsJpegOrPng_returns415() throws Exception {
        // text with an image name and Content-Type: rejected, the signature is what counts
        byte[] text = "just some text, not an image".getBytes(StandardCharsets.UTF_8);
        upload(new MockMultipartFile("file", "photo.jpg", "image/jpeg", text))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        upload(new MockMultipartFile("file", "photo.png", "image/png", text))
                .andExpect(status().isUnsupportedMediaType());
        assertThat(imageRepository.countByProductId(product.getId())).isZero();
    }

    @Test
    void uploadImage_pngRenamedToTxt_acceptedBySignature() throws Exception {
        JsonNode body = read(upload(new MockMultipartFile("file", "notes.txt", "text/plain", bytes(PNG_MAGIC, 64)))
                .andExpect(status().isCreated()));
        assertThat(files.typeOf(image(body).getStorageKey())).contains(ImageType.PNG);

        assertThat(imageRepository.countByProductId(product.getId())).isEqualTo(1);
    }

    @Test
    void uploadImage_webp_isAccepted() throws Exception {
        byte[] webp = new byte[64];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, webp, 0, 4);
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, webp, 8, 4);
        JsonNode body = read(upload(new MockMultipartFile("file", "a.webp", "image/webp", webp))
                .andExpect(status().isCreated()));
        assertThat(files.typeOf(image(body).getStorageKey())).contains(ImageType.WEBP);
    }

    @Test
    void uploadImage_over5Mb_returns413() throws Exception {
        upload(new MockMultipartFile("file", "big.png", "image/png", bytes(PNG_MAGIC, 5 * 1024 * 1024 + 1)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
        assertThat(imageRepository.countByProductId(product.getId())).isZero();
    }

    @Test
    void uploadImage_noFile_returns400() throws Exception {
        mockMvc.perform(multipart(imagesUrl()).with(MANAGER))
                .andExpect(status().isBadRequest());
    }

    @Test
    void uploadImage_eleventh_returns422() throws Exception {
        for (int i = 0; i < 10; i++) {
            upload(png("p" + i + ".png")).andExpect(status().isCreated());
        }
        upload(png("eleven.png"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));
        assertThat(imageRepository.countByProductId(product.getId())).isEqualTo(10);
    }

    @Test
    void uploadImage_unknownProduct_returns404() throws Exception {
        mockMvc.perform(multipart("/api/admin/products/{id}/images", UUID.randomUUID()).file(png("x.png")).with(MANAGER))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteImage_main_promotesNextAndRemovesFile() throws Exception {
        ProductImage first = image(read(upload(png("1.png"))));
        ProductImage second = image(read(upload(png("2.png"))));
        ProductImage third = image(read(upload(png("3.png"))));
        // put the third before the second, so "next by sort order" is the third
        reorder(first, third, second).andExpect(status().isOk());

        mockMvc.perform(delete(imagesUrl() + "/{imageId}", first.getPublicId()).with(MANAGER))
                .andExpect(status().isNoContent());

        assertThat(imageRepository.findById(first.getId())).isEmpty();
        assertThat(imageRepository.findById(third.getId()).orElseThrow().isMain()).isTrue();
        assertThat(imageRepository.findById(second.getId()).orElseThrow().isMain()).isFalse();
        assertThat(files.typeOf(first.getStorageKey())).isEmpty();
        // the other files stay
        assertThat(files.typeOf(third.getStorageKey())).isPresent();
    }

    @Test
    void deleteImage_otherProductsImage_returns404() throws Exception {
        ProductImage image = image(read(upload(png("1.png"))));
        Product other = fixtures.product(fixtures.category("Other"), "Other product", "1.00", 0, 10);

        mockMvc.perform(delete("/api/admin/products/{p}/images/{i}", other.getPublicId(), image.getPublicId()).with(MANAGER))
                .andExpect(status().isNotFound());
        assertThat(imageRepository.findById(image.getId())).isPresent();
    }

    @Test
    void setMain_otherImage_unsetsPreviousMain() throws Exception {
        ProductImage first = image(read(upload(png("1.png"))));
        ProductImage second = image(read(upload(png("2.png"))));

        mockMvc.perform(put(imagesUrl() + "/{imageId}/main", second.getPublicId()).with(MANAGER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.main").value(true));

        assertThat(imageRepository.findById(first.getId()).orElseThrow().isMain()).isFalse();
        assertThat(imageRepository.findById(second.getId()).orElseThrow().isMain()).isTrue();
    }

    @Test
    void reorder_missingImage_returns400() throws Exception {
        ProductImage first = image(read(upload(png("1.png"))));
        upload(png("2.png"));

        mockMvc.perform(put(imagesUrl() + "/order").with(MANAGER)
                        .contentType(APPLICATION_JSON).content(json(List.of(first.getPublicId()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reorder_allImages_listReturnedInNewOrder() throws Exception {
        ProductImage first = image(read(upload(png("1.png"))));
        ProductImage second = image(read(upload(png("2.png"))));

        reorder(second, first).andExpect(status().isOk());

        mockMvc.perform(get(imagesUrl()).with(STAFF))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].publicId").value(second.getPublicId().toString()))
                .andExpect(jsonPath("$[0].sortOrder").value(0))
                .andExpect(jsonPath("$[1].publicId").value(first.getPublicId().toString()))
                .andExpect(jsonPath("$[1].main").value(true));
    }

    @Test
    void images_staff_canReadButNotWrite() throws Exception {
        mockMvc.perform(get(imagesUrl()).with(STAFF)).andExpect(status().isOk());
        mockMvc.perform(multipart(imagesUrl()).file(png("x.png")).with(STAFF))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(imagesUrl()).with(TestAuth.asRole(1L, "CUSTOMER")))
                .andExpect(status().isForbidden());
    }

    // ---- helpers -----------------------------------------------------------

    private String imagesUrl() {
        return "/api/admin/products/" + product.getPublicId() + "/images";
    }

    private ResultActions upload(MockMultipartFile file) throws Exception {
        return mockMvc.perform(multipart(imagesUrl()).file(file).with(MANAGER));
    }

    private ResultActions reorder(ProductImage... images) throws Exception {
        List<UUID> ids = Arrays.stream(images).map(ProductImage::getPublicId).toList();
        return mockMvc.perform(put(imagesUrl() + "/order").with(MANAGER)
                .contentType(APPLICATION_JSON).content(json(ids)));
    }

    private JsonNode read(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private ProductImage image(JsonNode body) {
        UUID publicId = UUID.fromString(body.get("publicId").asText());
        return imageRepository.findByPublicIdAndProductId(publicId, product.getId()).orElseThrow();
    }

    private static MockMultipartFile png(String name) {
        return new MockMultipartFile("file", name, "image/png", bytes(PNG_MAGIC, 128));
    }

    private static MockMultipartFile jpeg(String name) {
        return new MockMultipartFile("file", name, "image/jpeg", bytes(JPEG_MAGIC, 128));
    }

    /** The signature followed by zeros, {@code size} bytes in total. */
    private static byte[] bytes(byte[] magic, int size) {
        byte[] data = new byte[size];
        System.arraycopy(magic, 0, data, 0, magic.length);
        return data;
    }
}
