package com.psar.catalog.mapper;

import com.psar.catalog.dto.response.ProductImageResponse;
import com.psar.catalog.entity.ProductImage;
import com.psar.common.mapper.CentralMapperConfig;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(config = CentralMapperConfig.class)
public interface ProductImageMapper {

    ProductImageResponse toResponse(ProductImage image);

    List<ProductImageResponse> toResponses(List<ProductImage> images);
}
