package com.psar.inventory.mapper;

import com.psar.common.mapper.CentralMapperConfig;
import com.psar.inventory.dto.request.SupplierRequest;
import com.psar.inventory.dto.response.SupplierResponse;
import com.psar.inventory.entity.Supplier;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(config = CentralMapperConfig.class)
public interface SupplierMapper {

    SupplierResponse toResponse(Supplier supplier);

    @Mapping(target = "publicId", ignore = true)
    Supplier toEntity(SupplierRequest request);

    @Mapping(target = "publicId", ignore = true)
    void update(SupplierRequest request, @MappingTarget Supplier supplier);
}
