package com.psar.catalog.service;

import com.psar.catalog.dto.response.ProductImageResponse;
import com.psar.catalog.entity.ProductImage;
import com.psar.catalog.mapper.ProductImageMapper;
import com.psar.catalog.repository.ProductImageRepository;
import com.psar.catalog.repository.ProductRepository;
import com.psar.common.exception.BadRequestException;
import com.psar.common.exception.BusinessRuleException;
import com.psar.common.exception.NotFoundException;
import com.psar.common.storage.StorageService;
import com.psar.common.storage.StoredFile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Product images. Rules: at most {@value #MAX_IMAGES} per product, exactly one main image while there is any
 * (the first upload becomes main), files are deleted from storage only after the DB change commits.
 * Every write locks the product row first, so concurrent requests for one product can't break these rules.
 */
@Service
@RequiredArgsConstructor
public class ProductImageService {

    static final int MAX_IMAGES = 10;
    private static final String FOLDER = "products";

    private final ProductImageRepository imageRepository;
    private final ProductRepository productRepository;
    private final ProductImageMapper imageMapper;
    private final StorageService storageService;

    @Transactional(readOnly = true)
    public List<ProductImageResponse> list(UUID productPublicId) {
        Long productId = productRepository.findByPublicId(productPublicId)
                .orElseThrow(() -> NotFoundException.of("Product")).getId();
        return imageMapper.toResponses(imageRepository.findAllByProductIdOrderBySortOrderAscIdAsc(productId));
    }

    @Transactional
    public ProductImageResponse upload(UUID productPublicId, MultipartFile file) {
        Long productId = lockProduct(productPublicId);
        List<ProductImage> images = imageRepository.findAllByProductIdOrderBySortOrderAscIdAsc(productId);
        if (images.size() >= MAX_IMAGES) {
            throw new BusinessRuleException("A product can have at most " + MAX_IMAGES + " images");
        }

        // validated (415/413) here; removed again from storage if this transaction rolls back
        StoredFile stored = storageService.upload(FOLDER, file);

        ProductImage image = new ProductImage();
        image.setProduct(productRepository.getReferenceById(productId));
        image.setImageUrl(stored.url());
        image.setStorageKey(stored.key());
        image.setMain(images.isEmpty());
        image.setSortOrder(images.stream().mapToInt(ProductImage::getSortOrder).max().orElse(-1) + 1);
        return imageMapper.toResponse(imageRepository.save(image));
    }

    @Transactional
    public ProductImageResponse setMain(UUID productPublicId, UUID imagePublicId) {
        Long productId = lockProduct(productPublicId);
        ProductImage target = findImage(imagePublicId, productId);
        if (!target.isMain()) {
            imageRepository.findAllByProductIdOrderBySortOrderAscIdAsc(productId).stream()
                    .filter(ProductImage::isMain)
                    .forEach(previous -> previous.setMain(false));
            // the unique "one main per product" index needs the old main cleared before the new one is set
            imageRepository.flush();
            target.setMain(true);
        }
        return imageMapper.toResponse(target);
    }

    /** {@code imageIds} must list every image of the product exactly once; the position becomes the sort order. */
    @Transactional
    public List<ProductImageResponse> reorder(UUID productPublicId, List<UUID> imageIds) {
        Long productId = lockProduct(productPublicId);
        List<ProductImage> images = imageRepository.findAllByProductIdOrderBySortOrderAscIdAsc(productId);
        Map<UUID, ProductImage> byId = images.stream()
                .collect(Collectors.toMap(ProductImage::getPublicId, Function.identity()));
        if (imageIds.size() != images.size() || !byId.keySet().equals(new HashSet<>(imageIds))) {
            throw new BadRequestException("The list must contain every image of the product exactly once",
                    Map.of("imageIds", "must match the product's images"));
        }
        for (int i = 0; i < imageIds.size(); i++) {
            byId.get(imageIds.get(i)).setSortOrder(i);
        }
        return imageMapper.toResponses(imageIds.stream().map(byId::get).toList());
    }

    /** Deleting the main image makes the next one (by sort order) the main image. */
    @Transactional
    public void delete(UUID productPublicId, UUID imagePublicId) {
        Long productId = lockProduct(productPublicId);
        ProductImage image = findImage(imagePublicId, productId);
        imageRepository.delete(image);
        // Hibernate runs deletes last; flush so the new main doesn't clash with the old one in the unique index
        imageRepository.flush();
        if (image.isMain()) {
            imageRepository.findAllByProductIdOrderBySortOrderAscIdAsc(productId).stream()
                    .findFirst()
                    .ifPresent(next -> next.setMain(true));
        }
        storageService.deleteAfterCommit(image.getStorageKey());
    }

    private Long lockProduct(UUID productPublicId) {
        return imageRepository.lockProductIdByPublicId(productPublicId)
                .orElseThrow(() -> NotFoundException.of("Product"));
    }

    private ProductImage findImage(UUID imagePublicId, Long productId) {
        return imageRepository.findByPublicIdAndProductId(imagePublicId, productId)
                .orElseThrow(() -> NotFoundException.of("Image"));
    }
}
