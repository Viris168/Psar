package com.psar.order.entity;

import com.psar.common.entity.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** One cart per customer, created on first use. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "carts")
public class Cart extends BaseEntity {

    /** Internal id of the owner (users.id). */
    @Column(name = "user_id", nullable = false, unique = true, updatable = false)
    private Long userId;

    @Setter(AccessLevel.NONE)
    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<CartItem> items = new ArrayList<>();

    public Optional<CartItem> item(Long productId) {
        return items.stream().filter(item -> item.getProductId().equals(productId)).findFirst();
    }

    public CartItem addItem(Long productId, int quantity) {
        CartItem item = new CartItem();
        item.setCart(this);
        item.setProductId(productId);
        item.setQuantity(quantity);
        items.add(item);
        return item;
    }

    public void removeItem(Long productId) {
        items.removeIf(item -> item.getProductId().equals(productId));
    }

    public void clear() {
        items.clear();
    }
}
