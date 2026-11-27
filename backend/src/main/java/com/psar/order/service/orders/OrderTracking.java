package com.psar.order.service.orders;

import com.psar.delivery.service.DeliveryCompanyService;
import com.psar.order.entity.Order;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** The courier's tracking link: the company's template with {@code {tracking_no}} filled in. */
@Component
@RequiredArgsConstructor
class OrderTracking {

    static final String PLACEHOLDER = "{tracking_no}";

    private final DeliveryCompanyService deliveryCompanyService;

    /** Null unless the order has a company with a link template and a tracking number. */
    String url(Order order) {
        if (order.getDeliveryCompanyId() == null || !StringUtils.hasText(order.getTrackingNo())) {
            return null;
        }
        String template = deliveryCompanyService.snapshot(order.getDeliveryCompanyId()).trackingUrl();
        if (!StringUtils.hasText(template) || !template.contains(PLACEHOLDER)) {
            return null;
        }
        return template.replace(PLACEHOLDER, URLEncoder.encode(order.getTrackingNo(), StandardCharsets.UTF_8));
    }
}
