package com.psar.order.entity;

import com.psar.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Quantity 1–99 (DB check). Prices are never stored here: the cart always shows current prices. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "cart_items")
public class CartItem extends BaseEntity {

    public static final int MAX_QUANTITY = 99;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    /** catalog's products.id (catalog entities are private to that module). */
    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "quantity", nullable = false)
    private int quantity;
}
