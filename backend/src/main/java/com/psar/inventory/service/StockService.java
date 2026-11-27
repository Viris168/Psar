package com.psar.inventory.service;

import com.psar.audit.service.AuditEntity;
import com.psar.audit.service.AuditFields;
import com.psar.audit.service.AuditService;
import com.psar.catalog.dto.response.ProductStockInfo;
import com.psar.catalog.service.ProductStockService;
import com.psar.common.exception.BadRequestException;
import com.psar.common.web.PageResponse;
import com.psar.inventory.dto.response.InventoryProductResponse;
import com.psar.inventory.dto.response.LowStockResponse;
import com.psar.inventory.dto.response.StockMovementResponse;
import com.psar.inventory.entity.MovementReason;
import com.psar.inventory.entity.MovementType;
import com.psar.inventory.entity.PurchaseOrder;
import com.psar.inventory.entity.StockMovement;
import com.psar.inventory.event.StockLowEvent;
import com.psar.inventory.mapper.StockMapper;
import com.psar.inventory.repository.PurchaseOrderRepository;
import com.psar.inventory.repository.StockMovementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The only code that changes product stock (ArchUnit: onlyStockServiceChangesProductQuantity).
 * Every change is ProductStockService's atomic UPDATE plus a stock_movements row, in one transaction.
 * Other inventory code (purchase orders) reads products through here too.
 */
@Service
@RequiredArgsConstructor
public class StockService {

    static final int MAX_PAGE_SIZE = 100;
    private static final Sort MOVEMENTS_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    private static final Set<String> LOW_STOCK_SORTABLE = Set.of("quantity", "name", "sku");
    private static final Sort LOW_STOCK_SORT = Sort.by(Sort.Order.asc("quantity"), Sort.Order.asc("name"));

    private final ProductStockService productStockService;
    private final StockMovementRepository stockMovementRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final StockMapper stockMapper;
    private final ApplicationEventPublisher events;
    private final AuditService auditService;

    // ---- stock changes -----------------------------------------------------------------------

    /** Adds stock (IN), e.g. a received purchase order. Joins the caller's transaction. */
    @Transactional
    public void receive(Long productId, int qty, MovementReason reason, Long referenceId, Long userId) {
        requirePositive(qty);
        int balance = productStockService.increase(productId, qty);
        record(productId, MovementType.IN, qty, balance, reason, referenceId, null, userId);
    }

    /**
     * Removes stock (OUT), e.g. Phase 4 checkout reserving items.
     * Not enough stock → 409 INSUFFICIENT_STOCK, nothing changes and no movement is written.
     * Publishes {@link StockLowEvent} if this takes the product to its minimum.
     */
    @Transactional
    public void remove(Long productId, int qty, MovementReason reason, Long referenceId, Long userId) {
        requirePositive(qty);
        int minStock = productStockService.require(productId).minStock();
        int balance = productStockService.decrease(productId, qty);
        record(productId, MovementType.OUT, -qty, balance, reason, referenceId, null, userId);
        publishIfCrossedMinimum(productId, balance, qty, minStock);
    }

    /** Manual change by a manager: DAMAGE, RETURN or CORRECTION; signed, not 0; never below 0 (409). */
    @Transactional
    public StockMovementResponse adjust(UUID productPublicId, int change, MovementReason reason, String note,
                                        Long userId) {
        if (change == 0) {
            throw new BadRequestException("Change must not be 0", Map.of("change", "must not be 0"));
        }
        if (reason == null || !MovementReason.MANUAL_ADJUSTMENT.contains(reason)) {
            throw new BadRequestException("Adjustment reason must be DAMAGE, RETURN or CORRECTION",
                    Map.of("reason", "must be DAMAGE, RETURN or CORRECTION"));
        }
        ProductStockInfo product = productStockService.require(productPublicId);
        int balance = change > 0
                ? productStockService.increase(product.id(), change)
                : productStockService.decrease(product.id(), -change);
        StockMovement movement = record(product.id(), MovementType.ADJUST, change, balance, reason, null,
                StringUtils.hasText(note) ? note.trim() : null, userId);
        // stock_movements has no public id: the row points at the product
        auditService.recordCreate(AuditEntity.STOCK_ADJUSTMENT, product.publicId().toString(),
                AuditFields.create().put("sku", product.sku()).put("name", product.name()).put("change", change)
                        .put("reason", reason).put("balanceAfter", balance).put("note", movement.getNote()).toMap());
        if (change < 0) {
            publishIfCrossedMinimum(product.id(), balance, -change, product.minStock());
        }
        return stockMapper.toResponse(movement, stockMapper.toProduct(product), null);
    }

    // ---- product lookups (inventory may not call ProductStockService itself) -------------------

    /** 404 if missing. */
    @Transactional(readOnly = true)
    public ProductStockInfo product(UUID productPublicId) {
        return productStockService.require(productPublicId);
    }

    /** 404 if missing. */
    @Transactional(readOnly = true)
    public ProductStockInfo product(Long productId) {
        return productStockService.require(productId);
    }

    // ---- reads -------------------------------------------------------------------------------

    /** All filters optional; {@code from} inclusive, {@code to} exclusive. Always newest first. */
    @Transactional(readOnly = true)
    public PageResponse<StockMovementResponse> movements(UUID productPublicId, MovementType type,
                                                         MovementReason reason, Instant from, Instant to,
                                                         Pageable pageable) {
        Map<Long, InventoryProductResponse> products = new HashMap<>();
        Specification<StockMovement> spec = (root, query, cb) -> cb.conjunction();
        if (productPublicId != null) {
            ProductStockInfo product = productStockService.require(productPublicId);
            products.put(product.id(), stockMapper.toProduct(product));
            spec = spec.and((root, query, cb) -> cb.equal(root.get("productId"), product.id()));
        }
        if (type != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("type"), type));
        }
        if (reason != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("reason"), reason));
        }
        if (from != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        }
        if (to != null) {
            spec = spec.and((root, query, cb) -> cb.lessThan(root.get("createdAt"), to));
        }
        Page<StockMovement> page = stockMovementRepository.findAll(spec,
                PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), MAX_PAGE_SIZE),
                        MOVEMENTS_SORT));

        // one lookup per distinct product on the page (usually one: the history of a single product)
        page.forEach(m -> products.computeIfAbsent(m.getProductId(),
                id -> stockMapper.toProduct(productStockService.require(id))));
        Map<Long, String> poNumbers = purchaseOrderNumbers(page);
        return PageResponse.from(page.map(m -> stockMapper.toResponse(m, products.get(m.getProductId()),
                m.getReason() == MovementReason.PURCHASE ? poNumbers.get(m.getReferenceId()) : null)));
    }

    /** Active products at or below their minimum stock, lowest quantity first by default. */
    @Transactional(readOnly = true)
    public PageResponse<LowStockResponse> lowStock(Pageable pageable) {
        Sort sort = Sort.by(pageable.getSort().stream()
                .filter(order -> LOW_STOCK_SORTABLE.contains(order.getProperty()))
                .toList());
        Pageable safe = PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), MAX_PAGE_SIZE),
                sort.isSorted() ? sort : LOW_STOCK_SORT);
        return PageResponse.from(productStockService.lowStock(safe).map(stockMapper::toLowStock));
    }

    // ---- helpers -----------------------------------------------------------------------------

    private StockMovement record(Long productId, MovementType type, int quantity, int balanceAfter,
                                 MovementReason reason, Long referenceId, String note, Long userId) {
        StockMovement movement = new StockMovement();
        movement.setProductId(productId);
        movement.setType(type);
        movement.setQuantity(quantity);
        movement.setBalanceAfter(balanceAfter);
        movement.setReason(reason);
        movement.setReferenceId(referenceId);
        movement.setNote(note);
        movement.setCreatedBy(userId);
        return stockMovementRepository.save(movement);
    }

    private Map<Long, String> purchaseOrderNumbers(Page<StockMovement> page) {
        Set<Long> poIds = page.stream()
                .filter(m -> m.getReason() == MovementReason.PURCHASE && m.getReferenceId() != null)
                .map(StockMovement::getReferenceId)
                .collect(Collectors.toSet());
        if (poIds.isEmpty()) {
            return Map.of();
        }
        return purchaseOrderRepository.findAllById(poIds).stream()
                .collect(Collectors.toMap(PurchaseOrder::getId, PurchaseOrder::getPoNo));
    }

    /**
     * Only on crossing (above the minimum before, at or below it now), so sales while already low don't
     * notify again. Listeners run after commit, so a rolled-back change notifies nobody.
     */
    private void publishIfCrossedMinimum(Long productId, int balance, int removed, int minStock) {
        if (balance <= minStock && balance + removed > minStock) {
            events.publishEvent(new StockLowEvent(productId));
        }
    }

    private static void requirePositive(int qty) {
        if (qty <= 0) {
            throw new IllegalArgumentException("qty must be positive: " + qty);
        }
    }
}
