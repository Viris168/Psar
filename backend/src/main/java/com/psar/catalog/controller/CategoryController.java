package com.psar.catalog.controller;

import com.psar.catalog.dto.response.CategoryTreeResponse;
import com.psar.catalog.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Storefront - categories")
@RestController
@RequestMapping("/api/public/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @Operation(summary = "Active category tree: top-level categories with their active subcategories (no login)")
    @GetMapping
    public List<CategoryTreeResponse> tree() {
        return categoryService.activeTree();
    }
}
