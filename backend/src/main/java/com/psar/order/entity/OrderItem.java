package com.psar.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** A line with the product's name, SKU and price as they were at checkout. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "product_name", nullable = false, length = 150, updatable = false)
    private String productName;

    @Column(name = "sku", nullable = false, length = 50, updatable = false)
    private String sku;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    /** Buy X get Y (Phase 6); these units are also reserved from stock. */
    @Column(name = "free_quantity", nullable = false, updatable = false)
    private int freeQuantity;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal unitPrice;

    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal discountAmount = BigDecimal.ZERO.setScale(2);

    @Column(name = "line_total", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal lineTotal;

    /** Units that leave the shop: what checkout reserves and cancel releases. */
    public int stockQuantity() {
        return quantity + freeQuantity;
    }
}
