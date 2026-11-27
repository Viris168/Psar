package com.psar.catalog.mapper;

import com.psar.catalog.dto.request.ProductRequest;
import com.psar.catalog.dto.response.AdminProductResponse;
import com.psar.catalog.dto.response.CategoryRef;
import com.psar.catalog.dto.response.ProductDetailResponse;
import com.psar.catalog.dto.response.ProductResponse;
import com.psar.catalog.entity.Product;
import com.psar.common.mapper.CentralMapperConfig;
import com.psar.inventory.dto.response.SupplierSummary;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.List;

/** Storefront responses expose only {@code inStock}, never cost price, supplier or quantity. */
@Mapper(config = CentralMapperConfig.class, uses = CategoryMapper.class)
public interface ProductMapper {

    @Mapping(target = "inStock", expression = "java(product.getQuantity() > 0)")
    ProductResponse toResponse(Product product, String mainImageUrl);

    @Mapping(target = "inStock", expression = "java(product.getQuantity() > 0)")
    @Mapping(target = "images", source = "images")
    @Mapping(target = "breadcrumb", source = "breadcrumb")
    ProductDetailResponse toDetail(Product product, List<ProductDetailResponse.Image> images,
                                   List<CategoryRef> breadcrumb);

    @Mapping(target = "publicId", source = "product.publicId")
    @Mapping(target = "name", source = "product.name")
    @Mapping(target = "supplier", source = "supplier")
    @Mapping(target = "mainImageUrl", source = "mainImageUrl")
    AdminProductResponse toAdminResponse(Product product, SupplierSummary supplier, String mainImageUrl);

    /**
     * Plain fields only. SKU, slug, category, supplier, prices and unit are set by ProductService
     * (they need normalising or checks). Null minStock/active (wrappers -> primitives) leave the value unchanged.
     */
    @Mapping(target = "publicId", ignore = true)
    @Mapping(target = "sku", ignore = true)
    @Mapping(target = "slug", ignore = true)
    @Mapping(target = "category", ignore = true)
    @Mapping(target = "supplierId", ignore = true)
    @Mapping(target = "costPrice", ignore = true)
    @Mapping(target = "sellPrice", ignore = true)
    @Mapping(target = "unit", ignore = true)
    void update(ProductRequest request, @MappingTarget Product product);
}
