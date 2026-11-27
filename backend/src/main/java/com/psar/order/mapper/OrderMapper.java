package com.psar.order.mapper;

import com.psar.common.mapper.CentralMapperConfig;
import com.psar.order.dto.response.AdminOrderDetailResponse;
import com.psar.order.dto.response.AdminOrderListItemResponse;
import com.psar.order.dto.response.AdminOrderTimelineEntryResponse;
import com.psar.order.dto.response.OrderDeliveryResponse;
import com.psar.order.dto.response.OrderDetailResponse;
import com.psar.order.dto.response.OrderItemResponse;
import com.psar.order.dto.response.OrderListItemResponse;
import com.psar.order.dto.response.OrderMoneyResponse;
import com.psar.order.dto.response.OrderTimelineEntryResponse;
import com.psar.order.entity.Order;
import com.psar.order.entity.OrderItem;
import com.psar.order.entity.OrderStatusHistory;
import com.psar.user.dto.response.UserSummary;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * Order entity → DTOs. The whole-order responses are assembled in default methods from the generated
 * parts, because they combine the order with data the services load (payment, users, tracking link).
 */
@Mapper(config = CentralMapperConfig.class)
public interface OrderMapper {

    OrderItemResponse toItem(OrderItem item);

    List<OrderItemResponse> toItems(List<OrderItem> items);

    OrderMoneyResponse toMoney(Order order);

    @Mapping(target = "latitude", source = "order.shippingLatitude")
    @Mapping(target = "longitude", source = "order.shippingLongitude")
    @Mapping(target = "landmark", source = "order.shippingLandmark")
    OrderDeliveryResponse toDelivery(Order order, String trackingUrl);

    OrderTimelineEntryResponse toTimelineEntry(OrderStatusHistory row);

    List<OrderTimelineEntryResponse> toTimeline(List<OrderStatusHistory> rows);

    OrderListItemResponse toListItem(Order order);

    @Mapping(target = "status", source = "row.status")
    @Mapping(target = "note", source = "row.note")
    @Mapping(target = "createdAt", source = "row.createdAt")
    @Mapping(target = "changedBy", source = "changedBy")
    AdminOrderTimelineEntryResponse toAdminTimelineEntry(OrderStatusHistory row, UserSummary changedBy);

    @Mapping(target = "createdAt", source = "order.createdAt")
    @Mapping(target = "publicId", source = "order.publicId")
    @Mapping(target = "assignedDriver", source = "assignedDriver")
    AdminOrderListItemResponse toAdminListItem(Order order, UserSummary assignedDriver);

    default OrderDetailResponse toDetail(Order order, String paymentStatus, String trackingUrl,
                                         List<OrderStatusHistory> history) {
        return new OrderDetailResponse(order.getPublicId(), order.getOrderNo(), order.getStatus().name(),
                order.getPaymentMethod().name(), paymentStatus, order.getExpiresAt(), order.getNote(),
                order.getCreatedAt(), toItems(order.getItems()), toMoney(order), toDelivery(order, trackingUrl),
                toTimeline(history));
    }

    default AdminOrderDetailResponse toAdminDetail(Order order, String paymentStatus, String trackingUrl,
                                                   UserSummary customer, UserSummary assignedDriver,
                                                   List<AdminOrderTimelineEntryResponse> timeline) {
        return new AdminOrderDetailResponse(order.getPublicId(), order.getOrderNo(), order.getStatus().name(),
                order.getPaymentMethod().name(), paymentStatus, order.getExpiresAt(), order.getNote(),
                order.getCreatedAt(), customer, assignedDriver, order.getAssignedAt(), toItems(order.getItems()),
                toMoney(order), toDelivery(order, trackingUrl), timeline);
    }
}
