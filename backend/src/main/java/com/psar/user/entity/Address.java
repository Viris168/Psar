package com.psar.user.entity;

import com.psar.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * CITY addresses need latitude/longitude (inside Cambodia); PROVINCE addresses need
 * a delivery company and courier branch. Both rules are enforced by DB check constraints.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "addresses")
public class Address extends BaseEntity {

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "label", length = 30)
    private String label;

    @Column(name = "receiver_name", nullable = false, length = 100)
    private String receiverName;

    @Column(name = "phone", nullable = false, length = 30)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_type", nullable = false, length = 20)
    private DeliveryType deliveryType;

    @Column(name = "province", nullable = false, length = 50)
    private String province;

    // CITY (Phnom Penh)
    @Column(name = "latitude", precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "map_address", columnDefinition = "TEXT")
    private String mapAddress;

    @Column(name = "district", length = 50)
    private String district;

    @Column(name = "commune", length = 50)
    private String commune;

    @Column(name = "house_no", length = 30)
    private String houseNo;

    @Column(name = "street", length = 50)
    private String street;

    @Column(name = "landmark", length = 150)
    private String landmark;

    // PROVINCE
    /** ID only: delivery companies belong to the delivery module. */
    @Column(name = "delivery_company_id")
    private Long deliveryCompanyId;

    @Column(name = "courier_branch", length = 150)
    private String courierBranch;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "is_default", nullable = false)
    private boolean defaultAddress = false;
}
