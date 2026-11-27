package com.psar.order.service.delivery;

import com.psar.order.service.pricing.PricingCalculator;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * CITY delivery fee by distance (pure: no Spring, no I/O). Distance is the Haversine (great-circle) distance from
 * the shop to the customer's pin; the fee is the first band whose {@code maxKm} is <b>greater than</b> the
 * distance, so a band's upper edge belongs to the next band (default: {@code < 5 km → 1.00},
 * {@code 5 – <15 km → 2.00}, {@code ≥ 15 km → 3.00}). The last band has no {@code maxKm} and takes the rest.
 * <p>
 * Configured by {@code app.shop.latitude/longitude} and {@code app.delivery.city-bands}
 * (see {@code order/config/DeliveryConfig}).
 */
public final class DeliveryFeeCalculator {

    /** Mean Earth radius, km. */
    static final double EARTH_RADIUS_KM = 6371.0088;

    private final double shopLatitude;
    private final double shopLongitude;
    private final List<Band> bands;

    /**
     * @param maxKm upper edge (exclusive) in km; {@code null} only for the last band
     * @param fee   the fee, scale 2
     */
    public record Band(BigDecimal maxKm, BigDecimal fee) {
        public Band {
            Objects.requireNonNull(fee, "band fee");
            if (fee.signum() < 0) {
                throw new IllegalArgumentException("band fee must not be negative");
            }
            if (maxKm != null && maxKm.signum() <= 0) {
                throw new IllegalArgumentException("band maxKm must be positive");
            }
            fee = PricingCalculator.money(fee);
        }
    }

    public DeliveryFeeCalculator(BigDecimal shopLatitude, BigDecimal shopLongitude, List<Band> bands) {
        Objects.requireNonNull(shopLatitude, "shop latitude");
        Objects.requireNonNull(shopLongitude, "shop longitude");
        if (bands == null || bands.isEmpty()) {
            throw new IllegalArgumentException("at least one delivery band is needed");
        }
        for (int i = 0; i < bands.size(); i++) {
            Band band = bands.get(i);
            boolean last = i == bands.size() - 1;
            if (last != (band.maxKm() == null)) {
                throw new IllegalArgumentException("only the last delivery band has no maxKm");
            }
            if (i > 0 && !last && band.maxKm().compareTo(bands.get(i - 1).maxKm()) <= 0) {
                throw new IllegalArgumentException("delivery bands must be in increasing maxKm order");
            }
        }
        this.shopLatitude = shopLatitude.doubleValue();
        this.shopLongitude = shopLongitude.doubleValue();
        this.bands = List.copyOf(bands);
    }

    /** The fee for a pin. */
    public BigDecimal fee(BigDecimal latitude, BigDecimal longitude) {
        return feeForDistance(distanceKm(latitude, longitude));
    }

    /** Great-circle distance from the shop to the pin, in km. */
    public double distanceKm(BigDecimal latitude, BigDecimal longitude) {
        Objects.requireNonNull(latitude, "latitude");
        Objects.requireNonNull(longitude, "longitude");
        return haversineKm(shopLatitude, shopLongitude, latitude.doubleValue(), longitude.doubleValue());
    }

    /** The fee of the first band whose upper edge is above {@code km}. */
    public BigDecimal feeForDistance(double km) {
        if (km < 0 || Double.isNaN(km)) {
            throw new IllegalArgumentException("distance must be a non-negative number");
        }
        BigDecimal distance = BigDecimal.valueOf(km);
        for (Band band : bands) {
            if (band.maxKm() == null || distance.compareTo(band.maxKm()) < 0) {
                return band.fee();
            }
        }
        throw new IllegalStateException("unreachable: the last band has no maxKm");
    }

    static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
