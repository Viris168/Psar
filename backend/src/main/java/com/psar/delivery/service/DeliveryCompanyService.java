package com.psar.delivery.service;

import com.psar.common.exception.BusinessRuleException;
import com.psar.delivery.repository.DeliveryCompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivery companies. Other modules (e.g. user addresses) call this service,
 * never DeliveryCompanyRepository.
 */
@Service
@RequiredArgsConstructor
public class DeliveryCompanyService {

    private final DeliveryCompanyRepository deliveryCompanyRepository;

    /** Used when saving a PROVINCE address. Missing and inactive look the same to the caller. */
    @Transactional(readOnly = true)
    public void assertActive(Long deliveryCompanyId) {
        if (deliveryCompanyId == null || !deliveryCompanyRepository.existsByIdAndActiveTrue(deliveryCompanyId)) {
            throw new BusinessRuleException("Delivery company is not available");
        }
    }
}
