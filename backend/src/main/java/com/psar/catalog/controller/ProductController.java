package com.psar.catalog.controller;

import com.psar.catalog.dto.response.ProductDetailResponse;
import com.psar.catalog.dto.response.ProductResponse;
import com.psar.catalog.service.ProductService;
import com.psar.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@Tag(name = "Storefront - products")
@RestController
@RequestMapping("/api/public/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @Operation(summary = "Search active products (no login). The category filter includes its subcategories.")
    @GetMapping
    public PageResponse<ProductResponse> search(
            @Parameter(description = "Category slug") @RequestParam(required = false) String category,
            @Parameter(description = "Name or SKU, case-insensitive") @RequestParam(required = false) String q,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) Boolean inStock,
            @Parameter(schema = @Schema(allowableValues = {"newest", "price_asc", "price_desc", "name"},
                    defaultValue = "newest"))
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Max 100") @RequestParam(defaultValue = "20") int size) {
        return productService.searchPublic(
                new ProductService.PublicFilter(category, q, minPrice, maxPrice, inStock, sort), page, size);
    }

    @Operation(summary = "Product page by slug: all images and the category breadcrumb (no login)")
    @GetMapping("/{slug}")
    public ProductDetailResponse get(@PathVariable String slug) {
        return productService.getPublic(slug);
    }
}
