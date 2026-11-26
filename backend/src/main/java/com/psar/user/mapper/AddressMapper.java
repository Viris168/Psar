package com.psar.user.mapper;

import com.psar.common.mapper.CentralMapperConfig;
import com.psar.user.dto.request.AddressRequest;
import com.psar.user.dto.response.AddressResponse;
import com.psar.user.entity.Address;
import com.psar.user.entity.DeliveryType;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(config = CentralMapperConfig.class)
public interface AddressMapper {

    @Mapping(target = "isDefault", source = "defaultAddress")
    AddressResponse toResponse(Address address);

    /** The service sets the owner (user) and handles the one-default-per-user rule. */
    @Mapping(target = "publicId", ignore = true)
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "defaultAddress", source = "isDefault")
    Address toEntity(AddressRequest request);

    /** Full replace (PUT): fields missing from the request become null. */
    @Mapping(target = "publicId", ignore = true)
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "defaultAddress", source = "isDefault")
    void update(AddressRequest request, @MappingTarget Address address);

    /** Drop the fields of the other delivery type, e.g. a map pin left over after switching CITY -> PROVINCE. */
    @AfterMapping
    default void clearOtherDeliveryTypeFields(@MappingTarget Address address) {
        if (address.getDeliveryType() == DeliveryType.CITY) {
            address.setDeliveryCompanyId(null);
            address.setCourierBranch(null);
        } else if (address.getDeliveryType() == DeliveryType.PROVINCE) {
            address.setLatitude(null);
            address.setLongitude(null);
            address.setMapAddress(null);
            address.setDistrict(null);
            address.setCommune(null);
            address.setHouseNo(null);
            address.setStreet(null);
            address.setLandmark(null);
        }
    }
}
