package com.psar.order.service.orders;

import com.psar.common.exception.ConflictException;
import com.psar.common.exception.ErrorCode;
import com.psar.common.exception.NotFoundException;
import com.psar.common.web.PageResponse;
import com.psar.order.dto.response.OrderDetailResponse;
import com.psar.order.dto.response.OrderListItemResponse;
import com.psar.order.entity.Order;
import com.psar.order.entity.OrderStatus;
import com.psar.order.mapper.OrderMapper;
import com.psar.order.repository.OrderRepository;
import com.psar.order.repository.OrderStatusHistoryRepository;
import com.psar.order.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.UUID;

/** The customer's own orders. Every lookup is scoped to the current user: someone else's order is a 404. */
@Service
@RequiredArgsConstructor
public class OrderService {

    static final int MAX_PAGE_SIZE = 100;
    /** Always newest first; the client's sort parameter is ignored. */
    private static final Sort LIST_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    static final String CUSTOMER_CANCEL_NOTE = "Cancelled by customer";

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final PaymentRepository paymentRepository;
    private final OrderWorkflow workflow;
    private final OrderTracking tracking;
    private final OrderMapper orderMapper;

    /** My orders, newest first, optionally of one status. */
    @Transactional(readOnly = true)
    public PageResponse<OrderListItemResponse> listMine(Long userId, OrderStatus status, Pageable pageable) {
        Specification<Order> spec = (root, query, cb) -> cb.equal(root.get("userId"), userId);
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        Pageable safe = PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), MAX_PAGE_SIZE),
                LIST_SORT);
        return PageResponse.from(orderRepository.findAll(spec, safe).map(orderMapper::toListItem));
    }

    @Transactional(readOnly = true)
    public OrderDetailResponse getMine(Long userId, UUID publicId) {
        return detail(findMine(userId, publicId));
    }

    /**
     * Cancel my order while it is PENDING_PAYMENT or CONFIRMED (409 INVALID_STATUS after that): stock back,
     * payment cancelled. The optional reason goes into the timeline note.
     */
    @Transactional
    public OrderDetailResponse cancelMine(Long userId, UUID publicId, String reason) {
        // lock first, then check the owner: a row loaded before the lock could hold a stale status
        Order order = workflow.lock(publicId);
        if (!order.getUserId().equals(userId)) {
            throw NotFoundException.of("Order");
        }
        if (!order.getStatus().isCancellableByCustomer()) {
            throw new ConflictException(ErrorCode.INVALID_STATUS, order.getStatus() == OrderStatus.CANCELLED
                    ? "Order %s is already cancelled".formatted(order.getOrderNo())
                    : "Order %s is already being prepared and can't be cancelled; please contact the shop"
                    .formatted(order.getOrderNo()));
        }
        String note = StringUtils.hasText(reason) ? CUSTOMER_CANCEL_NOTE + ": " + reason.trim() : CUSTOMER_CANCEL_NOTE;
        workflow.move(order, OrderStatus.CANCELLED, userId, note);
        return detail(order);
    }

    private Order findMine(Long userId, UUID publicId) {
        return orderRepository.findByPublicIdAndUserId(publicId, userId)
                .orElseThrow(() -> NotFoundException.of("Order"));
    }

    private OrderDetailResponse detail(Order order) {
        String paymentStatus = paymentRepository.findByOrderId(order.getId())
                .map(payment -> payment.getStatus().name()).orElse(null);
        return orderMapper.toDetail(order, paymentStatus, tracking.url(order),
                historyRepository.findAllByOrderIdOrderByIdAsc(order.getId()));
    }
}
