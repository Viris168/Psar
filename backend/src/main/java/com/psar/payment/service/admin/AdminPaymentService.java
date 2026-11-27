package com.psar.payment.service.admin;

import com.psar.common.exception.NotFoundException;
import com.psar.common.web.PageResponse;
import com.psar.order.dto.response.OrderSummary;
import com.psar.order.service.OrderLookupService;
import com.psar.payment.dto.response.AdminPaymentDetailResponse;
import com.psar.payment.dto.response.AdminPaymentListItemResponse;
import com.psar.payment.dto.response.PaymentEventResponse;
import com.psar.payment.entity.Payment;
import com.psar.payment.entity.PaymentMethod;
import com.psar.payment.entity.PaymentStatus;
import com.psar.payment.mapper.AdminPaymentMapper;
import com.psar.payment.repository.PaymentEventRepository;
import com.psar.payment.repository.PaymentRepository;
import com.psar.user.dto.response.UserSummary;
import com.psar.user.service.UserLookupService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** ADMIN / MANAGER payment screens: the list with filters, and one payment with its payment_events. */
@Service
@RequiredArgsConstructor
public class AdminPaymentService {

    static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> SORTABLE = Set.of("createdAt", "paidAt", "amount", "status");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final OrderLookupService orderLookupService;
    private final UserLookupService userLookupService;
    private final AdminPaymentMapper mapper;
    private final PaymentSettlementService settlementService;

    /** All optional. {@code from} inclusive, {@code to} exclusive (created at). */
    public record Filter(PaymentStatus status, PaymentMethod method, Boolean refundRequired, Instant from,
                         Instant to) {
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminPaymentListItemResponse> list(Filter filter, Pageable pageable) {
        Specification<Payment> spec = (root, query, cb) -> cb.conjunction();
        if (filter.status() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), filter.status()));
        }
        if (filter.method() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("method"), filter.method()));
        }
        if (filter.refundRequired() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("refundRequired"), filter.refundRequired()));
        }
        if (filter.from() != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), filter.from()));
        }
        if (filter.to() != null) {
            spec = spec.and((root, query, cb) -> cb.lessThan(root.get("createdAt"), filter.to()));
        }
        Page<Payment> page = paymentRepository.findAll(spec, safePage(pageable));
        // at most one page (≤ 100 rows) of order lookups
        return PageResponse.from(page.map(payment -> mapper.toListItem(payment, order(payment))));
    }

    @Transactional(readOnly = true)
    public AdminPaymentDetailResponse get(UUID publicId) {
        Payment payment = paymentRepository.findByPublicId(publicId)
                .orElseThrow(() -> NotFoundException.of("Payment"));
        List<PaymentEventResponse> events = paymentEventRepository.findAllByPaymentIdOrderByIdAsc(payment.getId())
                .stream().map(mapper::toEvent).toList();
        UserSummary refundedBy = payment.getRefundedBy() == null ? null
                : userLookupService.summaries(List.of(payment.getRefundedBy())).get(payment.getRefundedBy());
        return mapper.toDetail(payment, order(payment), refundedBy, events);
    }

    /** A late payment was returned by hand (see {@link PaymentSettlementService#markRefundedByHand}). */
    @Transactional
    public AdminPaymentDetailResponse markRefundedByHand(UUID publicId, String reason, Long actorId) {
        settlementService.markRefundedByHand(publicId, reason, actorId);
        return get(publicId);
    }

    private OrderSummary order(Payment payment) {
        return payment.getOrderId() == null ? null : orderLookupService.summary(payment.getOrderId());
    }

    private static Pageable safePage(Pageable pageable) {
        Sort sort = Sort.by(pageable.getSort().stream()
                .filter(order -> SORTABLE.contains(order.getProperty()))
                .toList());
        return PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), MAX_PAGE_SIZE),
                sort.isSorted() ? sort.and(Sort.by(Sort.Order.desc("id"))) : DEFAULT_SORT);
    }
}
