package com.psar.order.service.orders;

import com.psar.audit.service.AuditEntity;
import com.psar.audit.service.AuditFields;
import com.psar.audit.service.AuditService;
import com.psar.common.exception.BusinessRuleException;
import com.psar.common.exception.NotFoundException;
import com.psar.common.web.PageResponse;
import com.psar.order.dto.response.AdminOrderDetailResponse;
import com.psar.order.dto.response.AdminOrderListItemResponse;
import com.psar.order.dto.response.AdminOrderTimelineEntryResponse;
import com.psar.order.entity.DeliveryType;
import com.psar.order.entity.Order;
import com.psar.order.entity.OrderStatus;
import com.psar.order.entity.OrderStatusHistory;
import com.psar.order.mapper.OrderMapper;
import com.psar.order.repository.OrderRepository;
import com.psar.order.repository.OrderStatusHistoryRepository;
import com.psar.order.repository.PaymentRepository;
import com.psar.user.dto.response.UserSummary;
import com.psar.user.service.UserDirectoryService;
import com.psar.user.service.UserLookupService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Staff side of orders: list / detail, the status flow, driver and tracking number, and cancelling.
 * Every change locks the order row first; staff changes are written to the audit log.
 */
@Service
@RequiredArgsConstructor
public class AdminOrderService {

    static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> SORTABLE = Set.of("createdAt", "orderNo", "totalAmount", "status");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    /** Set elsewhere: PAID / REFUNDED by payments (Phase 5), CANCELLED through {@link #cancel}. */
    private static final Set<OrderStatus> NOT_BY_STATUS_CHANGE =
            Set.of(OrderStatus.PAID, OrderStatus.REFUNDED, OrderStatus.CANCELLED);

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final PaymentRepository paymentRepository;
    private final OrderWorkflow workflow;
    private final OrderTracking tracking;
    private final OrderMapper orderMapper;
    private final UserLookupService userLookupService;
    private final UserDirectoryService userDirectoryService;
    private final AuditService auditService;
    private final Clock clock;

    /** Filters for the staff list; all optional. */
    public record Filter(OrderStatus status, DeliveryType deliveryType, Instant from, Instant to, String q,
                         UUID assignedTo) {
    }

    /**
     * {@code from} inclusive, {@code to} exclusive (created at); {@code q} matches the order number or the
     * receiver's phone (part of it); {@code assignedTo} = the driver's publicId. Newest first by default.
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminOrderListItemResponse> list(Filter filter, Pageable pageable) {
        Specification<Order> spec = (root, query, cb) -> cb.conjunction();
        if (filter.status() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), filter.status()));
        }
        if (filter.deliveryType() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("deliveryType"), filter.deliveryType()));
        }
        if (filter.from() != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), filter.from()));
        }
        if (filter.to() != null) {
            spec = spec.and((root, query, cb) -> cb.lessThan(root.get("createdAt"), filter.to()));
        }
        if (StringUtils.hasText(filter.q())) {
            String like = "%" + escapeLike(filter.q().trim().toLowerCase(Locale.ROOT)) + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("orderNo")), like, '\\'),
                    cb.like(root.get("receiverPhone"), like, '\\')));
        }
        if (filter.assignedTo() != null) {
            Optional<Long> driverId = userLookupService.idOf(filter.assignedTo());
            spec = spec.and((root, query, cb) -> driverId
                    .map(id -> cb.equal(root.get("assignedStaffId"), id))
                    .orElseGet(cb::disjunction));
        }
        Page<Order> page = orderRepository.findAll(spec, safePage(pageable));

        Set<Long> driverIds = new HashSet<>();
        page.forEach(order -> {
            if (order.getAssignedStaffId() != null) {
                driverIds.add(order.getAssignedStaffId());
            }
        });
        Map<Long, UserSummary> drivers = userLookupService.summaries(driverIds);
        return PageResponse.from(page.map(order ->
                orderMapper.toAdminListItem(order,
                        order.getAssignedStaffId() == null ? null : drivers.get(order.getAssignedStaffId()))));
    }

    @Transactional(readOnly = true)
    public AdminOrderDetailResponse get(UUID publicId) {
        return detail(orderRepository.findByPublicId(publicId).orElseThrow(() -> NotFoundException.of("Order")));
    }

    /**
     * One step along the flow ({@link OrderStatus#canMoveTo}, else 409 INVALID_STATUS). CANCELLED, PAID and
     * REFUNDED are not set here (422).
     */
    @Transactional
    public AdminOrderDetailResponse changeStatus(UUID publicId, OrderStatus next, String note, Long actorId) {
        if (NOT_BY_STATUS_CHANGE.contains(next)) {
            throw new BusinessRuleException(next == OrderStatus.CANCELLED
                    ? "Use the cancel action to cancel an order"
                    : "%s is set by the payment, not by hand".formatted(next));
        }
        Order order = workflow.lock(publicId);
        OrderStatus from = order.getStatus();
        workflow.move(order, next, actorId, note);
        auditService.recordChanges(AuditEntity.ORDER, publicId.toString(),
                AuditFields.create().put("status", from),
                AuditFields.create().put("status", next));
        return detail(order);
    }

    /** CITY orders only (422); the user must be an active driver (422). Can be changed until the order is done. */
    @Transactional
    public AdminOrderDetailResponse assignDriver(UUID publicId, UUID driverPublicId) {
        Order order = workflow.lock(publicId);
        workflow.requireDeliveryType(order, DeliveryType.CITY,
                "Only Phnom Penh (CITY) orders get a driver; province orders go with a courier");
        workflow.requireOpen(order);
        Long driverId = userLookupService.idOf(driverPublicId)
                .filter(userDirectoryService::isActiveDriver)
                .orElseThrow(() -> new BusinessRuleException("This user is not an active driver",
                        Map.of("driverPublicId", "must be an active staff member with position Driver")));
        Long before = order.getAssignedStaffId();
        if (!driverId.equals(before)) {
            order.setAssignedStaffId(driverId);
            order.setAssignedAt(clock.instant());
            orderRepository.save(order);
            UUID previous = before == null ? null
                    : Optional.ofNullable(userLookupService.summaries(List.of(before)).get(before))
                    .map(UserSummary::publicId).orElse(null);
            auditService.recordChanges(AuditEntity.ORDER, publicId.toString(),
                    AuditFields.create().put("driver", previous),
                    AuditFields.create().put("driver", driverPublicId));
        }
        return detail(order);
    }

    /** PROVINCE orders only (422). Can be corrected until the order is done. */
    @Transactional
    public AdminOrderDetailResponse setTrackingNo(UUID publicId, String trackingNo) {
        Order order = workflow.lock(publicId);
        workflow.requireDeliveryType(order, DeliveryType.PROVINCE,
                "Only province orders have a courier tracking number");
        workflow.requireOpen(order);
        String before = order.getTrackingNo();
        String clean = trackingNo.trim();
        if (!Objects.equals(before, clean)) {
            order.setTrackingNo(clean);
            orderRepository.save(order);
            auditService.recordChanges(AuditEntity.ORDER, publicId.toString(),
                    AuditFields.create().put("trackingNo", before),
                    AuditFields.create().put("trackingNo", clean));
        }
        return detail(order);
    }

    /**
     * ADMIN / MANAGER: cancel any order that hasn't left the shop (else 409 INVALID_STATUS, also when already
     * cancelled). Stock back, payment cancelled; the reason is shown to the customer.
     */
    @Transactional
    public AdminOrderDetailResponse cancel(UUID publicId, String reason, Long actorId) {
        Order order = workflow.lock(publicId);
        OrderStatus from = order.getStatus();
        if (!from.isCancellable()) {
            throw OrderWorkflow.invalidStatus(order, OrderStatus.CANCELLED);
        }
        workflow.move(order, OrderStatus.CANCELLED, actorId, reason);
        auditService.recordChanges(AuditEntity.ORDER, publicId.toString(),
                AuditFields.create().put("status", from),
                AuditFields.create().put("status", OrderStatus.CANCELLED)
                        .put("reason", reason.trim()));
        return detail(order);
    }

    private AdminOrderDetailResponse detail(Order order) {
        List<OrderStatusHistory> history = historyRepository.findAllByOrderIdOrderByIdAsc(order.getId());
        Set<Long> userIds = new HashSet<>();
        userIds.add(order.getUserId());
        if (order.getAssignedStaffId() != null) {
            userIds.add(order.getAssignedStaffId());
        }
        history.stream().map(OrderStatusHistory::getChangedBy).filter(Objects::nonNull).forEach(userIds::add);
        Map<Long, UserSummary> users = userLookupService.summaries(userIds);

        List<AdminOrderTimelineEntryResponse> timeline = history.stream()
                .map(row -> orderMapper.toAdminTimelineEntry(row,
                        row.getChangedBy() == null ? null : users.get(row.getChangedBy())))
                .toList();
        String paymentStatus = paymentRepository.findByOrderId(order.getId())
                .map(payment -> payment.getStatus().name()).orElse(null);
        return orderMapper.toAdminDetail(order, paymentStatus, tracking.url(order), users.get(order.getUserId()),
                order.getAssignedStaffId() == null ? null : users.get(order.getAssignedStaffId()), timeline);
    }

    private static Pageable safePage(Pageable pageable) {
        Sort sort = Sort.by(pageable.getSort().stream()
                .filter(order -> SORTABLE.contains(order.getProperty()))
                .toList());
        return PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), MAX_PAGE_SIZE),
                sort.isSorted() ? sort.and(Sort.by(Sort.Order.desc("id"))) : DEFAULT_SORT);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
