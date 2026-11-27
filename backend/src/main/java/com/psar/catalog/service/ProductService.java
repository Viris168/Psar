package com.psar.catalog.service;

import com.psar.catalog.dto.request.ProductRequest;
import com.psar.catalog.dto.response.AdminProductResponse;
import com.psar.catalog.dto.response.CategoryRef;
import com.psar.catalog.dto.response.ProductDetailResponse;
import com.psar.catalog.dto.response.ProductResponse;
import com.psar.catalog.entity.Category;
import com.psar.catalog.entity.Product;
import com.psar.catalog.mapper.CategoryMapper;
import com.psar.catalog.mapper.ProductMapper;
import com.psar.catalog.repository.ProductRepository;
import com.psar.common.exception.BadRequestException;
import com.psar.common.exception.ConflictException;
import com.psar.common.exception.ErrorCode;
import com.psar.common.exception.NotFoundException;
import com.psar.common.web.PageResponse;
import com.psar.inventory.dto.response.SupplierSummary;
import com.psar.inventory.service.SupplierService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Products: admin CRUD and the storefront. Quantity is never written here: new products start at 0 and
 * stock only changes through inventory's StockService (purchase orders, adjustments).
 */
@Service
@RequiredArgsConstructor
public class ProductService {

    static final int MAX_PAGE_SIZE = 100;
    private static final String DEFAULT_UNIT = "pcs";

    private final ProductRepository productRepository;
    private final CategoryService categoryService;
    private final SupplierService supplierService;
    private final ProductMapper productMapper;
    private final CategoryMapper categoryMapper;

    // ---- storefront --------------------------------------------------------------------------

    public record PublicFilter(String category, String q, BigDecimal minPrice, BigDecimal maxPrice,
                               Boolean inStock, String sort) {
    }

    public record AdminFilter(String q, String category, UUID supplier, Boolean active, String sort) {
    }

    /** Active products in active categories. A category filter includes its subcategories. */
    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> searchPublic(PublicFilter filter, int page, int size) {
        if (filter.minPrice() != null && filter.maxPrice() != null
                && filter.minPrice().compareTo(filter.maxPrice()) > 0) {
            throw new BadRequestException("minPrice must not be greater than maxPrice");
        }
        List<Long> categoryIds = filter.category() == null || filter.category().isBlank()
                ? null : categoryService.idsWithChildren(filter.category(), true);
        Specification<Product> spec = allOf(
                ProductSpecifications.visibleInStore(),
                ProductSpecifications.inCategories(categoryIds),
                ProductSpecifications.matches(filter.q()),
                ProductSpecifications.priceAtLeast(filter.minPrice()),
                ProductSpecifications.priceAtMost(filter.maxPrice()),
                ProductSpecifications.inStock(filter.inStock()));
        Page<Product> products = productRepository.findAll(spec, pageRequest(page, size, filter.sort()));
        Map<Long, String> mainImages = mainImages(products);
        return PageResponse.from(products.map(p -> productMapper.toResponse(p, mainImages.get(p.getId()))));
    }

    /** Storefront product page: 404 when the product or its category is not active. */
    @Transactional(readOnly = true)
    public ProductDetailResponse getPublic(String slug) {
        Product product = productRepository.findWithCategoryBySlug(slug)
                .filter(ProductService::visibleInStore)
                .orElseThrow(() -> NotFoundException.of("Product"));
        List<ProductDetailResponse.Image> images = productRepository.findImages(product.getId()).stream()
                .map(row -> new ProductDetailResponse.Image(row.getUrl(), Boolean.TRUE.equals(row.getMain())))
                .toList();
        return productMapper.toDetail(product, images, breadcrumb(product.getCategory()));
    }

    // ---- admin -------------------------------------------------------------------------------

    /**
     * Admin list, active and inactive. {@code supplier} must be an active supplier (SupplierService only
     * resolves active ones for now), otherwise 422.
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminProductResponse> searchAdmin(AdminFilter filter, int page, int size) {
        List<Long> categoryIds = filter.category() == null || filter.category().isBlank()
                ? null : categoryService.idsWithChildren(filter.category(), false);
        Long supplierId = filter.supplier() == null ? null : supplierService.requireActiveId(filter.supplier());
        Specification<Product> spec = allOf(
                ProductSpecifications.matches(filter.q()),
                ProductSpecifications.inCategories(categoryIds),
                ProductSpecifications.supplier(supplierId),
                ProductSpecifications.active(filter.active()));
        Page<Product> products = productRepository.findAll(spec, pageRequest(page, size, filter.sort()));
        Map<Long, String> mainImages = mainImages(products);
        Map<Long, SupplierSummary> suppliers = supplierService.summaries(products.stream()
                .map(Product::getSupplierId).filter(Objects::nonNull).collect(Collectors.toSet()));
        return PageResponse.from(products.map(p -> productMapper.toAdminResponse(
                p, suppliers.get(p.getSupplierId()), mainImages.get(p.getId()))));
    }

    @Transactional(readOnly = true)
    public AdminProductResponse getAdmin(UUID publicId) {
        return toAdminResponse(find(publicId));
    }

    @Transactional
    public AdminProductResponse create(ProductRequest request) {
        String sku = normalizeSku(request.sku());
        if (productRepository.existsBySku(sku)) {
            throw skuTaken();
        }
        Product product = new Product();
        productMapper.update(request, product);
        product.setSku(sku);
        product.setCategory(categoryService.requireActive(request.categorySlug()));
        product.setSupplierId(request.supplierPublicId() == null
                ? null : supplierService.requireActiveId(request.supplierPublicId()));
        applyPrices(request, product);
        product.setUnit(request.unit() == null ? DEFAULT_UNIT : request.unit().trim());
        String base = SlugGenerator.slugify(request.name(), sku);
        product.setSlug(SlugGenerator.unique(base, productRepository::existsBySlug));
        // quantity is not set: the DB default 0 applies (Product.quantity is read-only for JPA)
        return toAdminResponse(productRepository.save(product));
    }

    /** Everything except quantity (not in the request). The slug is kept so links don't break. */
    @Transactional
    public AdminProductResponse update(UUID publicId, ProductRequest request) {
        Product product = find(publicId);
        String sku = normalizeSku(request.sku());
        if (!sku.equals(product.getSku()) && productRepository.existsBySkuAndIdNot(sku, product.getId())) {
            throw skuTaken();
        }
        if (!request.categorySlug().equals(product.getCategory().getSlug())) {
            product.setCategory(categoryService.requireActive(request.categorySlug()));
        }
        product.setSupplierId(resolveSupplierOnUpdate(product.getSupplierId(), request.supplierPublicId()));
        productMapper.update(request, product);
        product.setSku(sku);
        applyPrices(request, product);
        if (request.unit() != null) {
            product.setUnit(request.unit().trim());
        }
        return toAdminResponse(productRepository.save(product));
    }

    /** Soft delete (orders and stock movements keep their reference). Already inactive = no-op. */
    @Transactional
    public void delete(UUID publicId) {
        Product product = find(publicId);
        if (product.isActive()) {
            product.setActive(false);
            productRepository.save(product);
        }
    }

    // ---- helpers -----------------------------------------------------------------------------

    private Product find(UUID publicId) {
        return productRepository.findWithCategoryByPublicId(publicId)
                .orElseThrow(() -> NotFoundException.of("Product"));
    }

    private AdminProductResponse toAdminResponse(Product product) {
        SupplierSummary supplier = product.getSupplierId() == null
                ? null : supplierService.summaries(Set.of(product.getSupplierId())).get(product.getSupplierId());
        String mainImage = productRepository.findMainImages(List.of(product.getId())).stream()
                .findFirst().map(ProductRepository.MainImageRow::getImageUrl).orElse(null);
        return productMapper.toAdminResponse(product, supplier, mainImage);
    }

    /** An unchanged supplier is kept even if it was deactivated since; a new one must be active. */
    private Long resolveSupplierOnUpdate(Long currentId, UUID requested) {
        if (requested == null) {
            return null;
        }
        if (currentId != null) {
            SupplierSummary current = supplierService.summaries(Set.of(currentId)).get(currentId);
            if (current != null && current.publicId().equals(requested)) {
                return currentId;
            }
        }
        return supplierService.requireActiveId(requested);
    }

    private Map<Long, String> mainImages(Page<Product> products) {
        if (products.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = products.stream().map(Product::getId).toList();
        return productRepository.findMainImages(ids).stream()
                .collect(Collectors.toMap(ProductRepository.MainImageRow::getProductId,
                        ProductRepository.MainImageRow::getImageUrl, (a, b) -> a));
    }

    private List<CategoryRef> breadcrumb(Category category) {
        List<CategoryRef> crumbs = new ArrayList<>(2);
        if (category.getParent() != null) {
            crumbs.add(categoryMapper.toRef(category.getParent()));
        }
        crumbs.add(categoryMapper.toRef(category));
        return crumbs;
    }

    private static boolean visibleInStore(Product product) {
        Category category = product.getCategory();
        return product.isActive() && category.isActive()
                && (category.getParent() == null || category.getParent().isActive());
    }

    private static void applyPrices(ProductRequest request, Product product) {
        product.setCostPrice(money(request.costPrice()));
        product.setSellPrice(money(request.sellPrice()));
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String normalizeSku(String sku) {
        return sku.trim().toUpperCase(Locale.ROOT);
    }

    private static ConflictException skuTaken() {
        return new ConflictException(ErrorCode.SKU_ALREADY_EXISTS, "A product with this SKU already exists");
    }

    private static PageRequest pageRequest(int page, int size, String sort) {
        return PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE), ProductSort.parse(sort).sort());
    }

    @SafeVarargs
    private static Specification<Product> allOf(Specification<Product>... specs) {
        return Specification.allOf(Stream.of(specs).filter(Objects::nonNull).toList());
    }
}
