package com.psar.user.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Everything checkout copies into an order (the order module may not use user entities).
 * {@code deliveryType} is "CITY" or "PROVINCE"; {@code deliveryCompanyId} is internal (PROVINCE only).
 */
public record AddressSnapshot(
        UUID publicId,
        String deliveryType,
        String receiverName,
        String phone,
        String province,
        BigDecimal latitude,
        BigDecimal longitude,
        String mapAddress,
        String district,
        String commune,
        String houseNo,
        String street,
        String landmark,
        Long deliveryCompanyId,
        String courierBranch,
        String note
) {
    /** One line for the order's shipping_address: the parts that are filled in, comma separated. */
    public String singleLine() {
        StringBuilder line = new StringBuilder();
        for (String part : new String[]{houseNo, street, commune, district, mapAddress, province}) {
            if (part != null && !part.isBlank()) {
                if (!line.isEmpty()) {
                    line.append(", ");
                }
                line.append(part.trim());
            }
        }
        return line.toString();
    }
}
