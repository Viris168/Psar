package com.psar.user.service;

import com.psar.common.exception.BadRequestException;
import com.psar.common.exception.BusinessRuleException;
import com.psar.common.exception.NotFoundException;
import com.psar.common.security.AuthenticatedUser;
import com.psar.common.web.PageResponse;
import com.psar.delivery.service.DeliveryCompanyService;
import com.psar.user.dto.request.AddressRequest;
import com.psar.user.dto.response.AddressResponse;
import com.psar.user.entity.Address;
import com.psar.user.entity.DeliveryType;
import com.psar.user.mapper.AddressMapper;
import com.psar.user.repository.AddressRepository;
import com.psar.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A customer's delivery addresses. Every lookup is scoped to the caller
 * (someone else's address is a 404). Exactly one address is the default
 * as long as the user has any (DB: partial unique index on is_default).
 */
@Service
@RequiredArgsConstructor
public class AddressService {

    public static final int MAX_ADDRESSES_PER_USER = 10;
    static final int MAX_PAGE_SIZE = 100;

    // Phnom Penh bounding box for CITY (own driver) delivery pins
    static final BigDecimal PP_MIN_LAT = new BigDecimal("11.40");
    static final BigDecimal PP_MAX_LAT = new BigDecimal("11.75");
    static final BigDecimal PP_MIN_LNG = new BigDecimal("104.75");
    static final BigDecimal PP_MAX_LNG = new BigDecimal("105.05");

    /** Default address first, then newest. Client sort is ignored. */
    private static final Sort LIST_SORT = Sort.by(
            Sort.Order.desc("defaultAddress"), Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final AddressRepository addressRepository;
    private final UserRepository userRepository;
    private final AddressMapper addressMapper;
    private final DeliveryCompanyService deliveryCompanyService;

    @Transactional(readOnly = true)
    public PageResponse<AddressResponse> list(AuthenticatedUser me, Pageable pageable) {
        Pageable page = PageRequest.of(
                pageable.getPageNumber(), Math.min(pageable.getPageSize(), MAX_PAGE_SIZE), LIST_SORT);
        return PageResponse.from(addressRepository.findAllByUserId(me.userId(), page).map(addressMapper::toResponse));
    }

    @Transactional(readOnly = true)
    public AddressResponse get(AuthenticatedUser me, UUID publicId) {
        return addressMapper.toResponse(find(me, publicId));
    }

    /** The first address is always the default; isDefault=true moves the default to the new one. */
    @Transactional
    public AddressResponse create(AuthenticatedUser me, AddressRequest request) {
        validate(request);
        long count = addressRepository.countByUserId(me.userId());
        if (count >= MAX_ADDRESSES_PER_USER) {
            throw new BusinessRuleException("You can save at most " + MAX_ADDRESSES_PER_USER + " addresses");
        }
        boolean makeDefault = count == 0 || request.isDefault();
        if (makeDefault && count > 0) {
            addressRepository.clearDefault(me.userId());
        }
        Address address = addressMapper.toEntity(request);
        address.setUser(userRepository.getReferenceById(me.userId()));
        address.setDefaultAddress(makeDefault);
        return addressMapper.toResponse(addressRepository.saveAndFlush(address));
    }

    /**
     * Full replace. isDefault=true makes this the default; isDefault=false does not
     * un-default the current default (use another address's /default instead).
     */
    @Transactional
    public AddressResponse update(AuthenticatedUser me, UUID publicId, AddressRequest request) {
        Address address = find(me, publicId);
        validate(request);
        boolean wasDefault = address.isDefaultAddress();
        if (request.isDefault() && !wasDefault) {
            addressRepository.clearDefault(me.userId()); // clears the persistence context
            address = find(me, publicId);
        }
        addressMapper.update(request, address);
        address.setDefaultAddress(wasDefault || request.isDefault());
        return addressMapper.toResponse(addressRepository.saveAndFlush(address));
    }

    /** Deleting the default promotes the newest remaining address. */
    @Transactional
    public void delete(AuthenticatedUser me, UUID publicId) {
        Address address = find(me, publicId);
        boolean wasDefault = address.isDefaultAddress();
        addressRepository.delete(address);
        // flush the delete before promoting another row, or the one-default index sees two defaults
        addressRepository.flush();
        if (wasDefault) {
            addressRepository.findFirstByUserIdOrderByCreatedAtDescIdDesc(me.userId())
                    .ifPresent(newest -> newest.setDefaultAddress(true));
        }
    }

    @Transactional
    public AddressResponse setDefault(AuthenticatedUser me, UUID publicId) {
        Address address = find(me, publicId);
        if (!address.isDefaultAddress()) {
            addressRepository.clearDefault(me.userId()); // clears the persistence context
            address = find(me, publicId);
            address.setDefaultAddress(true);
            address = addressRepository.saveAndFlush(address);
        }
        return addressMapper.toResponse(address);
    }

    private Address find(AuthenticatedUser me, UUID publicId) {
        return addressRepository.findByPublicIdAndUserId(publicId, me.userId())
                .orElseThrow(() -> NotFoundException.of("Address"));
    }

    /** Rules Bean Validation can't check: CITY pin inside Phnom Penh, PROVINCE company active. */
    private void validate(AddressRequest request) {
        if (request.deliveryType() == DeliveryType.CITY) {
            Map<String, String> details = new LinkedHashMap<>();
            if (!within(request.latitude(), PP_MIN_LAT, PP_MAX_LAT)) {
                details.put("latitude", "must be inside Phnom Penh (" + PP_MIN_LAT + " to " + PP_MAX_LAT + ")");
            }
            if (!within(request.longitude(), PP_MIN_LNG, PP_MAX_LNG)) {
                details.put("longitude", "must be inside Phnom Penh (" + PP_MIN_LNG + " to " + PP_MAX_LNG + ")");
            }
            if (!details.isEmpty()) {
                throw new BadRequestException("City delivery is only available inside Phnom Penh", details);
            }
        } else if (request.deliveryType() == DeliveryType.PROVINCE) {
            deliveryCompanyService.assertActive(request.deliveryCompanyId());
        }
    }

    private static boolean within(BigDecimal value, BigDecimal min, BigDecimal max) {
        return value != null && value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
    }
}
