package com.psar.user.service;

import com.psar.common.exception.NotFoundException;
import com.psar.user.dto.response.AddressSnapshot;
import com.psar.user.entity.Address;
import com.psar.user.repository.AddressRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/** Addresses for other modules (checkout), as snapshots. */
@Service
@RequiredArgsConstructor
public class AddressLookupService {

    private final AddressRepository addressRepository;

    /** The user's own address; another user's address (or a missing one) is a 404. */
    @Transactional(readOnly = true)
    public AddressSnapshot requireOwned(Long userId, UUID addressPublicId) {
        Address address = addressRepository.findByPublicIdAndUserId(addressPublicId, userId)
                .orElseThrow(() -> NotFoundException.of("Address"));
        return new AddressSnapshot(address.getPublicId(), address.getDeliveryType().name(),
                address.getReceiverName(), address.getPhone(), address.getProvince(), address.getLatitude(),
                address.getLongitude(), address.getMapAddress(), address.getDistrict(), address.getCommune(),
                address.getHouseNo(), address.getStreet(), address.getLandmark(), address.getDeliveryCompanyId(),
                address.getCourierBranch(), address.getNote());
    }

    /** The same Phnom Penh box the address form uses for CITY pins. */
    public static boolean insidePhnomPenh(BigDecimal latitude, BigDecimal longitude) {
        return within(latitude, AddressService.PP_MIN_LAT, AddressService.PP_MAX_LAT)
                && within(longitude, AddressService.PP_MIN_LNG, AddressService.PP_MAX_LNG);
    }

    private static boolean within(BigDecimal value, BigDecimal min, BigDecimal max) {
        return value != null && value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
    }
}
