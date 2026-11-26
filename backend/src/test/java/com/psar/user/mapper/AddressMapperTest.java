package com.psar.user.mapper;

import com.psar.user.dto.request.AddressRequest;
import com.psar.user.entity.Address;
import com.psar.user.entity.DeliveryType;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class AddressMapperTest {

    private final AddressMapper mapper = Mappers.getMapper(AddressMapper.class);

    @Test
    void update_cityToProvince_clearsMapPinAndCityFields() {
        Address address = mapper.toEntity(cityRequest());
        assertThat(address.getLatitude()).isNotNull();

        mapper.update(provinceRequest(), address);

        assertThat(address.getDeliveryType()).isEqualTo(DeliveryType.PROVINCE);
        assertThat(address.getDeliveryCompanyId()).isEqualTo(3L);
        assertThat(address.getLatitude()).isNull();
        assertThat(address.getLongitude()).isNull();
        assertThat(address.getStreet()).isNull();
    }

    @Test
    void toEntity_isDefaultTrue_setsDefaultAddress() {
        Address address = mapper.toEntity(cityRequest());

        assertThat(address.isDefaultAddress()).isTrue();
        assertThat(address.getDeliveryCompanyId()).isNull();
    }

    private static AddressRequest cityRequest() {
        return new AddressRequest("Home", "Dara", "012345678", DeliveryType.CITY, "Phnom Penh",
                new BigDecimal("11.556400"), new BigDecimal("104.928200"), null, "Chamkar Mon", null,
                "12", "St 310", null,
                // a stale courier picked before switching to CITY must be dropped
                3L, "Old branch",
                null, true);
    }

    private static AddressRequest provinceRequest() {
        return new AddressRequest("Home", "Dara", "012345678", DeliveryType.PROVINCE, "Siem Reap",
                null, null, null, null, null, null, "St 310", null,
                3L, "Siem Reap branch",
                null, true);
    }
}
