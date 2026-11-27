package com.psar.catalog.service;

import com.psar.catalog.dto.response.ProductStockInfo;
import com.psar.catalog.repository.ProductRepository;
import com.psar.common.exception.ConflictException;
import com.psar.common.exception.ErrorCode;
import com.psar.common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The only way to change products.quantity. Called <b>only</b> by inventory's StockService
 * (ArchUnit: ModuleBoundaryTest), which writes the stock_movements row in the same transaction.
 * Each change is one atomic UPDATE, so concurrent buyers can never oversell.
 */
@Service
@RequiredArgsConstructor
public class ProductStockService {

    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public ProductStockInfo require(UUID productPublicId) {
        return productRepository.findStockInfoByPublicId(productPublicId)
                .orElseThrow(() -> NotFoundException.of("Product"));
    }

    @Transactional(readOnly = true)
    public ProductStockInfo require(Long productId) {
        return productRepository.findStockInfoById(productId)
                .orElseThrow(() -> NotFoundException.of("Product"));
    }

    /** Must run inside the caller's transaction (the movement row is written there). Returns the new quantity. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int increase(Long productId, int qty) {
        requirePositive(qty);
        if (productRepository.increaseQuantity(productId, qty) == 0) {
            throw NotFoundException.of("Product");
        }
        return currentQuantity(productId);
    }

    /**
     * Must run inside the caller's transaction. Returns the new quantity.
     * Not enough stock -> ConflictException(INSUFFICIENT_STOCK) and nothing changes.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int decrease(Long productId, int qty) {
        requirePositive(qty);
        if (productRepository.decreaseQuantity(productId, qty) == 0) {
            if (productRepository.findQuantityById(productId).isEmpty()) {
                throw NotFoundException.of("Product");
            }
            throw new ConflictException(ErrorCode.INSUFFICIENT_STOCK, "Not enough stock");
        }
        return currentQuantity(productId);
    }

    /** Active products at or below their minimum stock. */
    @Transactional(readOnly = true)
    public Page<ProductStockInfo> lowStock(Pageable pageable) {
        return productRepository.findLowStock(pageable);
    }

    private int currentQuantity(Long productId) {
        return productRepository.findQuantityById(productId).orElseThrow(() -> NotFoundException.of("Product"));
    }

    private static void requirePositive(int qty) {
        if (qty <= 0) {
            throw new IllegalArgumentException("qty must be positive: " + qty);
        }
    }
}
