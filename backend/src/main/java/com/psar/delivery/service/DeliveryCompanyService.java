package com.psar.delivery.service;

import com.psar.audit.service.AuditEntity;
import com.psar.audit.service.AuditFields;
import com.psar.audit.service.AuditService;
import com.psar.common.exception.BusinessRuleException;
import com.psar.common.exception.NotFoundException;
import com.psar.common.web.PageResponse;
import com.psar.delivery.dto.request.DeliveryCompanyRequest;
import com.psar.delivery.dto.response.DeliveryCompanyResponse;
import com.psar.delivery.entity.DeliveryCompany;
import com.psar.delivery.mapper.DeliveryCompanyMapper;
import com.psar.delivery.repository.DeliveryCompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * Delivery companies. Other modules (e.g. user addresses) call this service,
 * never DeliveryCompanyRepository.
 */
@Service
@RequiredArgsConstructor
public class DeliveryCompanyService {

    static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> SORTABLE = Set.of("name", "baseFee", "createdAt");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.ASC, "name");

    private final DeliveryCompanyRepository deliveryCompanyRepository;
    private final DeliveryCompanyMapper deliveryCompanyMapper;
    private final AuditService auditService;

    /** Used when saving a PROVINCE address. Missing and inactive look the same to the caller. */
    @Transactional(readOnly = true)
    public void assertActive(Long deliveryCompanyId) {
        if (deliveryCompanyId == null || !deliveryCompanyRepository.existsByIdAndActiveTrue(deliveryCompanyId)) {
            throw new BusinessRuleException("Delivery company is not available");
        }
    }

    /** Customer list: active companies only. */
    @Transactional(readOnly = true)
    public PageResponse<DeliveryCompanyResponse> listActive(Pageable pageable) {
        return PageResponse.from(deliveryCompanyRepository.findAllByActiveTrue(safePage(pageable))
                .map(deliveryCompanyMapper::toResponse));
    }

    /** Admin list: active and inactive. */
    @Transactional(readOnly = true)
    public PageResponse<DeliveryCompanyResponse> listAll(Pageable pageable) {
        return PageResponse.from(deliveryCompanyRepository.findAll(safePage(pageable))
                .map(deliveryCompanyMapper::toResponse));
    }

    @Transactional(readOnly = true)
    public DeliveryCompanyResponse get(Long id) {
        return deliveryCompanyMapper.toResponse(find(id));
    }

    @Transactional
    public DeliveryCompanyResponse create(DeliveryCompanyRequest request) {
        DeliveryCompany company = deliveryCompanyRepository.save(deliveryCompanyMapper.toEntity(request));
        auditService.recordCreate(AuditEntity.DELIVERY_COMPANY, company.getId().toString(),
                auditFields(company).toMap());
        return deliveryCompanyMapper.toResponse(company);
    }

    @Transactional
    public DeliveryCompanyResponse update(Long id, DeliveryCompanyRequest request) {
        DeliveryCompany company = find(id);
        AuditFields before = auditFields(company);
        deliveryCompanyMapper.update(request, company);
        auditService.recordChanges(AuditEntity.DELIVERY_COMPANY, id.toString(), before, auditFields(company));
        return deliveryCompanyMapper.toResponse(deliveryCompanyRepository.save(company));
    }

    /**
     * Soft delete (active = false): addresses keep their FK to the company, so a hard delete would fail.
     * Deleting an already inactive company is a no-op.
     */
    @Transactional
    public void delete(Long id) {
        DeliveryCompany company = find(id);
        if (company.isActive()) {
            auditService.recordDelete(AuditEntity.DELIVERY_COMPANY, id.toString(),
                    AuditFields.create().put("name", company.getName()).toMap());
        }
        company.setActive(false);
        deliveryCompanyRepository.save(company);
    }

    /** No public_id yet (open item): audit rows use the id, which is what the API exposes too. */
    private static AuditFields auditFields(DeliveryCompany company) {
        return AuditFields.create().put("name", company.getName()).put("logoUrl", company.getLogoUrl())
                .put("phone", company.getPhone()).put("trackingUrl", company.getTrackingUrl())
                .put("baseFee", company.getBaseFee()).put("active", company.isActive());
    }

    private DeliveryCompany find(Long id) {
        return deliveryCompanyRepository.findById(id).orElseThrow(() -> NotFoundException.of("Delivery company"));
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
