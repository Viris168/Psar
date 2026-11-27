package com.psar.catalog.controller;

import com.psar.catalog.dto.request.CategoryRequest;
import com.psar.catalog.dto.response.AdminCategoryResponse;
import com.psar.catalog.service.CategoryService;
import com.psar.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin - categories")
@RestController
@RequestMapping("/api/admin/categories")
@RequiredArgsConstructor
public class AdminCategoryController {

    private final CategoryService categoryService;

    @Operation(summary = "List all categories, active and inactive (sort: sortOrder, name, createdAt)")
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','STAFF')")
    public PageResponse<AdminCategoryResponse> list(
            @ParameterObject @PageableDefault(size = 50) Pageable pageable) {
        return categoryService.listAll(pageable);
    }

    @Operation(summary = "Create a category (parentSlug = null for top level; only two levels)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public AdminCategoryResponse create(@Valid @RequestBody CategoryRequest request) {
        return categoryService.create(request);
    }

    @Operation(summary = "Update a category (the slug never changes)")
    @PutMapping("/{slug}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public AdminCategoryResponse update(@PathVariable String slug, @Valid @RequestBody CategoryRequest request) {
        return categoryService.update(slug, request);
    }

    @Operation(summary = "Soft delete a category; 422 while it has active products or subcategories")
    @DeleteMapping("/{slug}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public void delete(@PathVariable String slug) {
        categoryService.delete(slug);
    }
}
