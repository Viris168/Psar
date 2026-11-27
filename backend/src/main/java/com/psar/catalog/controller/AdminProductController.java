package com.psar.catalog.controller;

import com.psar.catalog.dto.request.ProductRequest;
import com.psar.catalog.dto.response.AdminProductResponse;
import com.psar.catalog.service.ProductService;
import com.psar.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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

import java.util.UUID;

@Tag(name = "Admin - products")
@RestController
@RequestMapping("/api/admin/products")
@RequiredArgsConstructor
public class AdminProductController {

    private final ProductService productService;

    @Operation(summary = "List products, active and inactive, with cost price, supplier and stock")
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','STAFF')")
    public PageResponse<AdminProductResponse> list(
            @Parameter(description = "Name or SKU, case-insensitive") @RequestParam(required = false) String q,
            @Parameter(description = "Category slug (includes subcategories)")
            @RequestParam(required = false) String category,
            @Parameter(description = "Supplier publicId (must be an active supplier)")
            @RequestParam(required = false) UUID supplier,
            @RequestParam(required = false) Boolean active,
            @Parameter(schema = @Schema(allowableValues = {"newest", "price_asc", "price_desc", "name"},
                    defaultValue = "newest"))
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Max 100") @RequestParam(defaultValue = "20") int size) {
        return productService.searchAdmin(new ProductService.AdminFilter(q, category, supplier, active, sort), page, size);
    }

    @Operation(summary = "Get one product")
    @GetMapping("/{publicId}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','STAFF')")
    public AdminProductResponse get(@PathVariable UUID publicId) {
        return productService.getAdmin(publicId);
    }

    @Operation(summary = "Create a product. Quantity always starts at 0 (stock comes from purchase orders/adjustments)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public AdminProductResponse create(@Valid @RequestBody ProductRequest request) {
        return productService.create(request);
    }

    @Operation(summary = "Update a product (everything except quantity; the slug never changes)")
    @PutMapping("/{publicId}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public AdminProductResponse update(@PathVariable UUID publicId, @Valid @RequestBody ProductRequest request) {
        return productService.update(publicId, request);
    }

    @Operation(summary = "Soft delete a product (sets active = false)")
    @DeleteMapping("/{publicId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public void delete(@PathVariable UUID publicId) {
        productService.delete(publicId);
    }
}
