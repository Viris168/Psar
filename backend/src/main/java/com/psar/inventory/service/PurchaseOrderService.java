package com.psar.inventory.service;

import com.psar.catalog.dto.response.ProductStockInfo;
import com.psar.common.exception.BadRequestException;
import com.psar.common.exception.BusinessRuleException;
import com.psar.common.exception.ConflictException;
import com.psar.common.exception.ErrorCode;
import com.psar.common.exception.NotFoundException;
import com.psar.common.web.PageResponse;
import com.psar.inventory.dto.request.CreatePurchaseOrderRequest;
import com.psar.inventory.dto.request.PurchaseOrderItemRequest;
import com.psar.inventory.dto.response.PurchaseOrderDetailResponse;
import com.psar.inventory.dto.response.PurchaseOrderItemResponse;
import com.psar.inventory.dto.response.PurchaseOrderResponse;
import com.psar.inventory.entity.MovementReason;
import com.psar.inventory.entity.PurchaseOrder;
import com.psar.inventory.entity.PurchaseOrderItem;
import com.psar.inventory.entity.PurchaseOrderStatus;
import com.psar.inventory.entity.Supplier;
import com.psar.inventory.mapper.PurchaseOrderMapper;
import com.psar.inventory.mapper.StockMapper;
import com.psar.inventory.repository.PurchaseOrderRepository;
import com.psar.inventory.repository.SupplierRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Purchase orders: PENDING → RECEIVED (stock added through StockService) or PENDING → CANCELLED.
 * Receive and cancel lock the PO row, so a double click or two managers can't receive it twice.
 */
@Service
@RequiredArgsConstructor
public class PurchaseOrderService {

    static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> SORTABLE = Set.of("createdAt", "poNo", "totalAmount", "status");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final SupplierRepository supplierRepository;
    private final StockService stockService;
    private final PurchaseOrderMapper purchaseOrderMapper;
    private final StockMapper stockMapper;

    @Transactional(readOnly = true)
    public PageResponse<PurchaseOrderResponse> list(PurchaseOrderStatus status, UUID supplierPublicId,
                                                    Pageable pageable) {
        Specification<PurchaseOrder> spec = (root, query, cb) -> cb.conjunction();
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (supplierPublicId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("supplier").get("publicId"), supplierPublicId));
        }
        return PageResponse.from(purchaseOrderRepository.findAll(spec, safePage(pageable))
                .map(purchaseOrderMapper::toResponse));
    }

    @Transactional(readOnly = true)
    public PurchaseOrderDetailResponse get(String poNo) {
        return toDetail(purchaseOrderRepository.findByPoNo(poNo).orElseThrow(() -> NotFoundException.of("Purchase order")));
    }

    /** The total is always calculated here: Σ quantity × unitCost, HALF_UP, scale 2. */
    @Transactional
    public PurchaseOrderDetailResponse create(CreatePurchaseOrderRequest request, Long userId) {
        Supplier supplier = supplierRepository.findByPublicId(request.supplierPublicId())
                .filter(Supplier::isActive)
                .orElseThrow(() -> new BusinessRuleException("Supplier is not available"));

        Set<UUID> seen = new HashSet<>();
        for (PurchaseOrderItemRequest item : request.items()) {
            if (!seen.add(item.productPublicId())) {
                throw new BadRequestException("The same product appears twice",
                        Map.of("items", "product " + item.productPublicId() + " is listed more than once"));
            }
        }

        PurchaseOrder po = new PurchaseOrder();
        po.setSupplier(supplier);
        po.setNote(StringUtils.hasText(request.note()) ? request.note().trim() : null);
        po.setCreatedBy(userId);
        BigDecimal total = BigDecimal.ZERO;
        for (PurchaseOrderItemRequest line : request.items()) {
            ProductStockInfo product = stockService.product(line.productPublicId());
            PurchaseOrderItem item = new PurchaseOrderItem();
            item.setProductId(product.id());
            item.setQuantity(line.quantity());
            item.setUnitCost(line.unitCost().setScale(2, RoundingMode.HALF_UP));
            po.addItem(item);
            total = total.add(item.lineTotal());
        }
        po.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP));
        return toDetail(purchaseOrderRepository.saveAndFlush(po));
    }

    /** PENDING only: adds every item to stock (one IN movement each) and marks the PO RECEIVED. */
    @Transactional
    public PurchaseOrderDetailResponse receive(String poNo, Long userId) {
        PurchaseOrder po = lockPending(poNo, "received");
        for (PurchaseOrderItem item : po.getItems()) {
            stockService.receive(item.getProductId(), item.getQuantity(), MovementReason.PURCHASE, po.getId(), userId);
        }
        po.setStatus(PurchaseOrderStatus.RECEIVED);
        po.setReceivedAt(Instant.now());
        return toDetail(po);
    }

    /** PENDING only. No stock was added, so nothing to undo. */
    @Transactional
    public PurchaseOrderDetailResponse cancel(String poNo) {
        PurchaseOrder po = lockPending(poNo, "cancelled");
        po.setStatus(PurchaseOrderStatus.CANCELLED);
        po.setCancelledAt(Instant.now());
        return toDetail(po);
    }

    private PurchaseOrder lockPending(String poNo, String action) {
        PurchaseOrder po = purchaseOrderRepository.findByPoNoForUpdate(poNo)
                .orElseThrow(() -> NotFoundException.of("Purchase order"));
        if (po.getStatus() != PurchaseOrderStatus.PENDING) {
            throw new ConflictException(ErrorCode.INVALID_STATUS,
                    "Only a PENDING purchase order can be " + action + " (this one is " + po.getStatus() + ")");
        }
        return po;
    }

    /** One product lookup per item; a purchase order has only a handful of lines. */
    private PurchaseOrderDetailResponse toDetail(PurchaseOrder po) {
        List<PurchaseOrderItemResponse> lines = po.getItems().stream()
                .map(item -> purchaseOrderMapper.toItemResponse(item,
                        stockMapper.toProduct(stockService.product(item.getProductId()))))
                .toList();
        return purchaseOrderMapper.toDetailResponse(po, lines);
    }

    /** Caps the page size and only allows sorting on known properties. */
    private static Pageable safePage(Pageable pageable) {
        Sort sort = Sort.by(pageable.getSort().stream()
                .filter(order -> SORTABLE.contains(order.getProperty()))
                .toList());
        return PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), MAX_PAGE_SIZE),
                sort.isSorted() ? sort : DEFAULT_SORT);
    }
}
