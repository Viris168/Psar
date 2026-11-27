package com.psar.catalog.controller;

import com.psar.catalog.dto.response.ProductImageResponse;
import com.psar.catalog.service.ProductImageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/** A product has at most 10 images, so the list is not paginated. */
@Tag(name = "Admin - product images")
@RestController
@RequestMapping("/api/admin/products/{publicId}/images")
@RequiredArgsConstructor
public class AdminProductImageController {

    private final ProductImageService productImageService;

    @Operation(summary = "List a product's images in display order")
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','STAFF')")
    public List<ProductImageResponse> list(@PathVariable UUID publicId) {
        return productImageService.list(publicId);
    }

    @Operation(summary = "Upload an image (JPEG, PNG or WebP, max 5 MB). The first image becomes the main image")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ProductImageResponse upload(@PathVariable UUID publicId,
                                       // optional here so a missing file gets a 400 from the service, not a 500
                                       @RequestParam(value = "file", required = false) MultipartFile file) {
        return productImageService.upload(publicId, file);
    }

    @Operation(summary = "Make this image the main image")
    @PutMapping("/{imageId}/main")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ProductImageResponse setMain(@PathVariable UUID publicId, @PathVariable UUID imageId) {
        return productImageService.setMain(publicId, imageId);
    }

    @Operation(summary = "Reorder images: send every image id of the product once, in the new order")
    @PutMapping("/order")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public List<ProductImageResponse> reorder(@PathVariable UUID publicId,
                                              @RequestBody @NotEmpty @Size(max = 10) List<@NotNull UUID> imageIds) {
        return productImageService.reorder(publicId, imageIds);
    }

    @Operation(summary = "Delete an image (the next image becomes main if this was the main one)")
    @DeleteMapping("/{imageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public void delete(@PathVariable UUID publicId, @PathVariable UUID imageId) {
        productImageService.delete(publicId, imageId);
    }
}
