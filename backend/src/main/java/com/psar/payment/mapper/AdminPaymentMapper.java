package com.psar.payment.mapper;

import com.psar.common.mapper.CentralMapperConfig;
import com.psar.order.dto.response.OrderSummary;
import com.psar.payment.dto.response.AdminPaymentDetailResponse;
import com.psar.payment.dto.response.AdminPaymentListItemResponse;
import com.psar.payment.dto.response.PaymentEventResponse;
import com.psar.payment.entity.Payment;
import com.psar.payment.entity.PaymentEvent;
import com.psar.user.dto.response.UserSummary;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/** Admin payment screens. {@code order} may be null (a payment without an order, e.g. subscriptions later). */
@Mapper(config = CentralMapperConfig.class)
public interface AdminPaymentMapper {

    @Mapping(target = "publicId", source = "payment.publicId")
    @Mapping(target = "status", source = "payment.status")
    @Mapping(target = "createdAt", source = "payment.createdAt")
    @Mapping(target = "orderPublicId", source = "order.publicId")
    @Mapping(target = "orderNo", source = "order.orderNo")
    AdminPaymentListItemResponse toListItem(Payment payment, OrderSummary order);

    @Mapping(target = "publicId", source = "payment.publicId")
    @Mapping(target = "status", source = "payment.status")
    @Mapping(target = "createdAt", source = "payment.createdAt")
    @Mapping(target = "orderPublicId", source = "order.publicId")
    @Mapping(target = "orderNo", source = "order.orderNo")
    @Mapping(target = "orderStatus", source = "order.status")
    @Mapping(target = "refundedBy", source = "refundedBy")
    AdminPaymentDetailResponse toDetail(Payment payment, OrderSummary order, UserSummary refundedBy,
                                        List<PaymentEventResponse> events);

    PaymentEventResponse toEvent(PaymentEvent event);
}
