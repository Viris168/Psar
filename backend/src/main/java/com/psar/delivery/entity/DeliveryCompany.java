package com.psar.delivery.entity;

import com.psar.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "delivery_companies")
public class DeliveryCompany extends BaseEntity {

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "logo_url")
    private String logoUrl;

    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "tracking_url")
    private String trackingUrl;

    @Column(name = "base_fee", nullable = false, precision = 12, scale = 2)
    private BigDecimal baseFee = BigDecimal.ZERO.setScale(2);

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
