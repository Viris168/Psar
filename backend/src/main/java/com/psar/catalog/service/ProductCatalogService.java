package com.psar.catalog.service;

import com.psar.catalog.dto.response.SellableProduct;
import com.psar.catalog.entity.Category;
import com.psar.catalog.entity.Product;
import com.psar.catalog.repository.ProductRepository;
import com.psar.common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Current prices and availability for the order module (cart, checkout). */
@Service
@RequiredArgsConstructor
public class ProductCatalogService {

    private final ProductRepository productRepository;

    /** By internal id; missing ids are simply absent from the map. */
    @Transactional(readOnly = true)
    public Map<Long, SellableProduct> sellable(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        List<Product> products = productRepository.findAllWithCategoryByIdIn(productIds);
        Map<Long, String> mainImages = productRepository.findMainImages(
                        products.stream().map(Product::getId).toList()).stream()
                .collect(Collectors.toMap(ProductRepository.MainImageRow::getProductId,
                        ProductRepository.MainImageRow::getImageUrl, (a, b) -> a));
        return products.stream()
                .map(product -> toSellable(product, mainImages.get(product.getId())))
                .collect(Collectors.toMap(SellableProduct::id, Function.identity()));
    }

    /** By public id; 404 if missing (an inactive product is returned with {@code purchasable = false}). */
    @Transactional(readOnly = true)
    public SellableProduct sellable(UUID productPublicId) {
        Long id = productRepository.findByPublicId(productPublicId)
                .orElseThrow(() -> NotFoundException.of("Product")).getId();
        return sellable(List.of(id)).get(id);
    }

    private static SellableProduct toSellable(Product product, String mainImageUrl) {
        Category category = product.getCategory();
        return new SellableProduct(product.getId(), product.getPublicId(), product.getSlug(), product.getSku(),
                product.getName(), product.getSellPrice(), product.getUnit(), mainImageUrl, product.getQuantity(),
                category.getId(), category.getParent() == null ? null : category.getParent().getId(),
                purchasable(product));
    }

    /** Same rule as the storefront: product, category and parent category all active. */
    private static boolean purchasable(Product product) {
        Category category = product.getCategory();
        return product.isActive() && category.isActive()
                && (category.getParent() == null || category.getParent().isActive());
    }
}
