package com.psar.catalog.mapper;

import com.psar.catalog.dto.request.CategoryRequest;
import com.psar.catalog.dto.response.AdminCategoryResponse;
import com.psar.catalog.dto.response.CategoryRef;
import com.psar.catalog.dto.response.CategoryTreeResponse;
import com.psar.catalog.entity.Category;
import com.psar.common.mapper.CentralMapperConfig;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.List;

@Mapper(config = CentralMapperConfig.class)
public interface CategoryMapper {

    @Mapping(target = "parentSlug", source = "parent.slug")
    AdminCategoryResponse toAdminResponse(Category category);

    CategoryRef toRef(Category category);

    CategoryTreeResponse toTree(Category category, List<CategoryTreeResponse> children);

    /** Slug and parent are set by CategoryService (slug is generated once and never changes). */
    @Mapping(target = "slug", ignore = true)
    @Mapping(target = "parent", ignore = true)
    void update(CategoryRequest request, @MappingTarget Category category);
}
