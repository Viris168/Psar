package com.psar.inventory.service;

import com.psar.common.exception.BusinessRuleException;
import com.psar.inventory.dto.response.SupplierSummary;
import com.psar.inventory.entity.Supplier;
import com.psar.inventory.repository.SupplierRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Suppliers. The catalog module (products) calls this service, never SupplierRepository.
 */
@Service
@RequiredArgsConstructor
public class SupplierService {

    private final SupplierRepository supplierRepository;

    /** Used when saving a product. Missing and inactive look the same to the caller (422). */
    @Transactional(readOnly = true)
    public Long requireActiveId(UUID supplierPublicId) {
        return supplierRepository.findByPublicId(supplierPublicId)
                .filter(Supplier::isActive)
                .map(Supplier::getId)
                .orElseThrow(() -> new BusinessRuleException("Supplier is not available"));
    }

    /** One query for a page of products. Ids that don't exist are simply missing from the map. */
    @Transactional(readOnly = true)
    public Map<Long, SupplierSummary> summaries(Collection<Long> supplierIds) {
        if (supplierIds.isEmpty()) {
            return Map.of();
        }
        return supplierRepository.findAllById(supplierIds).stream()
                .collect(Collectors.toMap(Supplier::getId, s -> new SupplierSummary(s.getPublicId(), s.getName())));
    }
}
