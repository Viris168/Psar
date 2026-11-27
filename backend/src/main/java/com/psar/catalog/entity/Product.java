package com.psar.catalog.entity;

import com.psar.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "products")
public class Product extends BaseEntity {

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId = UUID.randomUUID();

    @Column(name = "sku", nullable = false, unique = true, length = 50)
    private String sku;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "name_kh", length = 150)
    private String nameKh;

    /** Public identifier used in storefront URLs. Kept when the product is renamed. */
    @Column(name = "slug", nullable = false, unique = true, length = 180)
    private String slug;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    /** ID only: suppliers belong to the inventory module. */
    @Column(name = "supplier_id")
    private Long supplierId;

    /** Required (no default). Never returned to customers. */
    @Column(name = "cost_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal costPrice;

    @Column(name = "sell_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal sellPrice;

    /**
     * Read-only for JPA: new products start at the DB default 0, and the value only changes through
     * ProductStockService's atomic UPDATEs (called by inventory's StockService, which writes the
     * stock_movements row). Saving a Product loaded earlier can never overwrite it with a stale value.
     * After a stock change, an already loaded Product still holds the old number in memory.
     */
    @Setter(AccessLevel.NONE)
    @Column(name = "quantity", nullable = false, insertable = false, updatable = false)
    private int quantity;

    @Column(name = "min_stock", nullable = false)
    private int minStock = 10;

    @Column(name = "unit", nullable = false, length = 20)
    private String unit = "pcs";

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
