package com.psar.catalog.service;

import com.psar.catalog.dto.request.CategoryRequest;
import com.psar.catalog.dto.response.AdminCategoryResponse;
import com.psar.catalog.dto.response.CategoryTreeResponse;
import com.psar.catalog.entity.Category;
import com.psar.catalog.mapper.CategoryMapper;
import com.psar.catalog.repository.CategoryRepository;
import com.psar.catalog.repository.ProductRepository;
import com.psar.common.exception.BusinessRuleException;
import com.psar.common.exception.NotFoundException;
import com.psar.common.web.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Categories: two levels only (top level + subcategories). Rules kept here:
 * <ul>
 *   <li>a subcategory can't have children, and a category with children can't become a subcategory (422);</li>
 *   <li>an active subcategory always has an active parent, so "category is active" is enough for the storefront;</li>
 *   <li>delete is soft and refused while the category has active products or active subcategories (422).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class CategoryService {

    static final int MAX_PAGE_SIZE = 100;
    private static final String FALLBACK_SLUG = "category";
    private static final Set<String> SORTABLE = Set.of("name", "sortOrder", "createdAt");
    private static final Sort DEFAULT_SORT = Sort.by("sortOrder", "name");

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final CategoryMapper categoryMapper;

    /** Storefront: active top-level categories, each with its active subcategories. */
    @Transactional(readOnly = true)
    public List<CategoryTreeResponse> activeTree() {
        List<Category> active = categoryRepository.findAllByActiveTrue(DEFAULT_SORT);
        Map<Long, List<CategoryTreeResponse>> childrenByParent = new LinkedHashMap<>();
        for (Category category : active) {
            if (category.getParent() != null) {
                childrenByParent.computeIfAbsent(category.getParent().getId(), id -> new ArrayList<>())
                        .add(categoryMapper.toTree(category, List.of()));
            }
        }
        return active.stream()
                .filter(category -> category.getParent() == null)
                .map(category -> categoryMapper.toTree(category,
                        childrenByParent.getOrDefault(category.getId(), List.of())))
                .toList();
    }

    /** Admin: all categories, active and inactive. */
    @Transactional(readOnly = true)
    public PageResponse<AdminCategoryResponse> listAll(Pageable pageable) {
        return PageResponse.from(categoryRepository.findAll(safePage(pageable)).map(categoryMapper::toAdminResponse));
    }

    @Transactional
    public AdminCategoryResponse create(CategoryRequest request) {
        Category category = new Category();
        categoryMapper.update(request, category);
        Category parent = resolveParent(request.parentSlug(), null);
        category.setParent(parent);
        if (category.isActive() && parent != null && !parent.isActive()) {
            throw new BusinessRuleException("Parent category is not active");
        }
        String base = SlugGenerator.slugify(request.name(), FALLBACK_SLUG);
        category.setSlug(SlugGenerator.unique(base, categoryRepository::existsBySlug));
        return categoryMapper.toAdminResponse(categoryRepository.save(category));
    }

    @Transactional
    public AdminCategoryResponse update(String slug, CategoryRequest request) {
        Category category = find(slug);
        boolean wasActive = category.isActive();
        Category parent = resolveParent(request.parentSlug(), category);
        categoryMapper.update(request, category);
        category.setParent(parent);
        if (wasActive && !category.isActive()) {
            assertCanDeactivate(category);
        }
        if (category.isActive() && parent != null && !parent.isActive()) {
            throw new BusinessRuleException("Parent category is not active");
        }
        return categoryMapper.toAdminResponse(categoryRepository.save(category));
    }

    /** Soft delete. Deleting an already inactive category is a no-op. */
    @Transactional
    public void delete(String slug) {
        Category category = find(slug);
        if (!category.isActive()) {
            return;
        }
        assertCanDeactivate(category);
        category.setActive(false);
        categoryRepository.save(category);
    }

    // ---- used by ProductService --------------------------------------------------------------

    /** For saving a product. Missing and inactive look the same (422). */
    @Transactional(readOnly = true)
    public Category requireActive(String slug) {
        return categoryRepository.findBySlug(slug)
                .filter(Category::isActive)
                .orElseThrow(() -> new BusinessRuleException("Category is not available"));
    }

    /**
     * Ids of the category and its subcategories, for filtering products.
     * {@code activeOnly}: the storefront treats an inactive category as missing (404).
     */
    @Transactional(readOnly = true)
    public List<Long> idsWithChildren(String slug, boolean activeOnly) {
        Category category = categoryRepository.findBySlug(slug)
                .filter(c -> !activeOnly || c.isActive())
                .orElseThrow(() -> NotFoundException.of("Category"));
        List<Long> ids = new ArrayList<>();
        ids.add(category.getId());
        ids.addAll(categoryRepository.findIdsByParentId(category.getId()));
        return ids;
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** Null slug = top level. Enforces the two-level rule. {@code self} is null on create. */
    private Category resolveParent(String parentSlug, Category self) {
        if (parentSlug == null || parentSlug.isBlank()) {
            return null;
        }
        Category parent = categoryRepository.findBySlug(parentSlug)
                .orElseThrow(() -> new BusinessRuleException("Parent category does not exist"));
        if (self != null && parent.getId().equals(self.getId())) {
            throw new BusinessRuleException("A category can't be its own parent");
        }
        if (parent.getParent() != null) {
            throw new BusinessRuleException("Only two category levels are allowed: a subcategory can't have children");
        }
        if (self != null && categoryRepository.existsByParentId(self.getId())) {
            throw new BusinessRuleException("A category with subcategories can't become a subcategory");
        }
        return parent;
    }

    private void assertCanDeactivate(Category category) {
        if (productRepository.existsByCategoryIdAndActiveTrue(category.getId())) {
            throw new BusinessRuleException("Category still has active products");
        }
        if (categoryRepository.existsByParentIdAndActiveTrue(category.getId())) {
            throw new BusinessRuleException("Category still has active subcategories");
        }
    }

    private Category find(String slug) {
        return categoryRepository.findBySlug(slug).orElseThrow(() -> NotFoundException.of("Category"));
    }

    private static Pageable safePage(Pageable pageable) {
        Sort sort = Sort.by(pageable.getSort().stream()
                .filter(order -> SORTABLE.contains(order.getProperty()))
                .toList());
        return PageRequest.of(pageable.getPageNumber(),
                Math.min(pageable.getPageSize(), MAX_PAGE_SIZE),
                sort.isSorted() ? sort : DEFAULT_SORT);
    }
}
