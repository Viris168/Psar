package com.psar.order.service.refund;

import com.psar.audit.service.AuditEntity;
import com.psar.audit.service.AuditFields;
import com.psar.audit.service.AuditService;
import com.psar.common.exception.ConflictException;
import com.psar.common.exception.ErrorCode;
import com.psar.inventory.service.StockService;
import com.psar.order.dto.response.AdminOrderDetailResponse;
import com.psar.order.entity.Order;
import com.psar.order.entity.OrderItem;
import com.psar.order.entity.OrderStatus;
import com.psar.order.entity.PaymentMethod;
import com.psar.order.service.orders.AdminOrderService;
import com.psar.order.service.orders.OrderWorkflow;
import com.psar.payment.service.admin.PaymentSettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ADMIN / MANAGER refunds (task/task5.md, Agent C). The refund is <b>recorded</b>: the money is returned by
 * hand (the KHQR sandbox can't refund).
 * <ul>
 *   <li>PAID / PROCESSING KHQR order → CANCELLED (stock released by the workflow), payment REFUNDED</li>
 *   <li>DELIVERED → REFUNDED, payment REFUNDED; stock back (RELEASE) only if {@code restock}</li>
 *   <li>anything else, incl. a second refund → 409 INVALID_STATUS, nothing changes</li>
 * </ul>
 * The order row is locked first, so two refunds of one order run one after the other. The customer is
 * notified through the workflow's {@code OrderStatusChangedEvent}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private final OrderWorkflow workflow;
    private final PaymentSettlementService settlementService;
    private final StockService stockService;
    private final AuditService auditService;
    private final AdminOrderService adminOrderService;

    @Transactional
    public AdminOrderDetailResponse refund(UUID publicId, String reason, boolean restock, Long actorId) {
        Order order = workflow.lock(publicId);
        OrderStatus from = order.getStatus();
        OrderStatus next = target(order);
        String cleanReason = reason.trim();

        UUID paymentPublicId = settlementService.refundForOrder(order.getId(), cleanReason, actorId);
        workflow.move(order, next, actorId, cleanReason);
        boolean stockBack = next == OrderStatus.CANCELLED || restock;
        if (next == OrderStatus.REFUNDED && restock) {
            Map<Long, Integer> quantities = order.getItems().stream()
                    .collect(Collectors.toMap(OrderItem::getProductId, OrderItem::stockQuantity, Integer::sum));
            if (!quantities.isEmpty()) {
                stockService.release(quantities, order.getId(), actorId);
            }
        }
        auditService.recordChanges(AuditEntity.PAYMENT, paymentPublicId.toString(),
                AuditFields.create().put("status", "SUCCESS").put("orderStatus", from)
                        .put("refundReason", null).put("stockBack", null),
                AuditFields.create().put("status", "REFUNDED").put("orderStatus", next)
                        .put("refundReason", cleanReason).put("stockBack", stockBack));
        log.info("Order {} refunded ({} → {}, stock back: {})", order.getOrderNo(), from, next, stockBack);
        return adminOrderService.get(publicId);
    }

    private static OrderStatus target(Order order) {
        return switch (order.getStatus()) {
            case PAID, PROCESSING -> {
                if (order.getPaymentMethod() != PaymentMethod.KHQR) {
                    throw new ConflictException(ErrorCode.INVALID_STATUS,
                            "Order %s is cash on delivery and not paid yet: cancel it instead"
                                    .formatted(order.getOrderNo()));
                }
                yield OrderStatus.CANCELLED;
            }
            case DELIVERED -> OrderStatus.REFUNDED;
            default -> throw new ConflictException(ErrorCode.INVALID_STATUS,
                    "Order %s is %s and can't be refunded".formatted(order.getOrderNo(), order.getStatus()));
        };
    }
}
