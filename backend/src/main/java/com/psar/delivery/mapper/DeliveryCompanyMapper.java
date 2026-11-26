package com.psar.delivery.mapper;

import com.psar.common.mapper.CentralMapperConfig;
import com.psar.delivery.dto.request.DeliveryCompanyRequest;
import com.psar.delivery.dto.response.DeliveryCompanyResponse;
import com.psar.delivery.entity.DeliveryCompany;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;

@Mapper(config = CentralMapperConfig.class)
public interface DeliveryCompanyMapper {

    DeliveryCompanyResponse toResponse(DeliveryCompany company);

    DeliveryCompany toEntity(DeliveryCompanyRequest request);

    void update(DeliveryCompanyRequest request, @MappingTarget DeliveryCompany company);
}
