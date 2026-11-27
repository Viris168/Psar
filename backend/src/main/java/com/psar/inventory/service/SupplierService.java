package com.psar.inventory.service;

import com.psar.audit.service.AuditEntity;
import com.psar.audit.service.AuditFields;
import com.psar.audit.service.AuditService;
import com.psar.common.exception.BusinessRuleException;
import com.psar.common.exception.NotFoundException;
import com.psar.common.web.PageResponse;
import com.psar.inventory.dto.request.SupplierRequest;
import com.psar.inventory.dto.response.SupplierResponse;
import com.psar.inventory.dto.response.SupplierSummary;
import com.psar.inventory.entity.PurchaseOrderStatus;
import com.psar.inventory.entity.Supplier;
import com.psar.inventory.mapper.SupplierMapper;
import com.psar.inventory.repository.SupplierRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Suppliers. The catalog module (products) calls this service, never SupplierRepository.
 */
@Service
@RequiredArgsConstructor
public class SupplierService {

    static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> SORTABLE = Set.of("name", "createdAt");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.ASC, "name");

    private final SupplierRepository supplierRepository;
    private final SupplierMapper supplierMapper;
    private final AuditService auditService;

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

    /** Admin list. {@code q} matches name, phone or email (case-insensitive); {@code active} null = all. */
    @Transactional(readOnly = true)
    public PageResponse<SupplierResponse> list(String q, Boolean active, Pageable pageable) {
        Specification<Supplier> spec = (root, query, cb) -> cb.conjunction();
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("name")), like, '\\'),
                    cb.like(cb.lower(root.get("phone")), like, '\\'),
                    cb.like(cb.lower(root.get("email")), like, '\\')));
        }
        if (active != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("active"), active));
        }
        return PageResponse.from(supplierRepository.findAll(spec, safePage(pageable)).map(supplierMapper::toResponse));
    }

    @Transactional(readOnly = true)
    public SupplierResponse get(UUID publicId) {
        return supplierMapper.toResponse(find(publicId));
    }

    @Transactional
    public SupplierResponse create(SupplierRequest request) {
        Supplier supplier = supplierMapper.toEntity(request);
        supplier.setName(supplier.getName().trim());
        supplier = supplierRepository.save(supplier);
        auditService.recordCreate(AuditEntity.SUPPLIER, supplier.getPublicId().toString(),
                auditFields(supplier).toMap());
        return supplierMapper.toResponse(supplier);
    }

    @Transactional
    public SupplierResponse update(UUID publicId, SupplierRequest request) {
        Supplier supplier = find(publicId);
        AuditFields before = auditFields(supplier);
        supplierMapper.update(request, supplier);
        supplier.setName(supplier.getName().trim());
        auditService.recordChanges(AuditEntity.SUPPLIER, publicId.toString(), before, auditFields(supplier));
        return supplierMapper.toResponse(supplierRepository.saveAndFlush(supplier));
    }

    /**
     * Soft delete (active = false): products and purchase orders keep their FK. Refused (422) while the
     * supplier still has PENDING purchase orders: receive or cancel them first.
     */
    @Transactional
    public void delete(UUID publicId) {
        Supplier supplier = find(publicId);
        if (supplierRepository.hasPurchaseOrdersWithStatus(supplier.getId(), PurchaseOrderStatus.PENDING)) {
            throw new BusinessRuleException("Supplier has pending purchase orders");
        }
        if (supplier.isActive()) {
            auditService.recordDelete(AuditEntity.SUPPLIER, publicId.toString(),
                    AuditFields.create().put("name", supplier.getName()).toMap());
        }
        supplier.setActive(false);
    }

    private static AuditFields auditFields(Supplier supplier) {
        return AuditFields.create().put("name", supplier.getName()).put("phone", supplier.getPhone())
                .put("email", supplier.getEmail()).put("address", supplier.getAddress())
                .put("note", supplier.getNote()).put("active", supplier.isActive());
    }

    private Supplier find(UUID publicId) {
        return supplierRepository.findByPublicId(publicId).orElseThrow(() -> NotFoundException.of("Supplier"));
    }

    /** Caps the page size and only allows sorting on known properties (unknown ones would be a 500). */
    private static Pageable safePage(Pageable pageable) {
        Sort sort = Sort.by(pageable.getSort().stream()
                .filter(order -> SORTABLE.contains(order.getProperty()))
                .toList());
        return PageRequest.of(pageable.getPageNumber(),
                Math.min(pageable.getPageSize(), MAX_PAGE_SIZE),
                sort.isSorted() ? sort : DEFAULT_SORT);
    }
}
