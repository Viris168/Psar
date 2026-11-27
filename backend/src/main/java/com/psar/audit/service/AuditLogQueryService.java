package com.psar.audit.service;

import com.psar.audit.dto.response.AuditLogResponse;
import com.psar.audit.entity.AuditAction;
import com.psar.audit.entity.AuditLog;
import com.psar.audit.mapper.AuditLogMapper;
import com.psar.audit.repository.AuditLogRepository;
import com.psar.common.exception.BadRequestException;
import com.psar.common.exception.NotFoundException;
import com.psar.common.web.PageResponse;
import com.psar.user.dto.response.UserSummary;
import com.psar.user.service.UserLookupService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Admin reads of audit_logs. Always newest first; the rows themselves are never changed. */
@Service
@RequiredArgsConstructor
public class AuditLogQueryService {

    static final int MAX_PAGE_SIZE = 100;
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final AuditLogRepository auditLogRepository;
    private final UserLookupService userLookupService;
    private final AuditLogMapper auditLogMapper;

    /**
     * All filters optional. {@code user} is the actor's publicId (unknown → empty page); {@code entity} is
     * case-insensitive; {@code from} inclusive, {@code to} exclusive.
     */
    public record Filter(UUID user, AuditAction action, String entity, String entityId, Instant from, Instant to) {
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> list(Filter filter, int page, int size) {
        if (filter.from() != null && filter.to() != null && !filter.from().isBefore(filter.to())) {
            throw new BadRequestException("from must be before to", Map.of("from", "must be before to"));
        }
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE), NEWEST_FIRST);
        Specification<AuditLog> spec = (root, query, cb) -> cb.conjunction();
        if (filter.user() != null) {
            Optional<Long> userId = userLookupService.idOf(filter.user());
            if (userId.isEmpty()) {
                return PageResponse.from(Page.empty(pageRequest));
            }
            spec = spec.and((root, query, cb) -> cb.equal(root.get("userId"), userId.get()));
        }
        if (filter.action() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("action"), filter.action()));
        }
        if (StringUtils.hasText(filter.entity())) {
            String entity = filter.entity().trim().toUpperCase(Locale.ROOT);
            spec = spec.and((root, query, cb) -> cb.equal(root.get("entity"), entity));
        }
        if (StringUtils.hasText(filter.entityId())) {
            String entityId = filter.entityId().trim();
            spec = spec.and((root, query, cb) -> cb.equal(root.get("entityId"), entityId));
        }
        if (filter.from() != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), filter.from()));
        }
        if (filter.to() != null) {
            spec = spec.and((root, query, cb) -> cb.lessThan(root.get("createdAt"), filter.to()));
        }
        Page<AuditLog> logs = auditLogRepository.findAll(spec, pageRequest);
        Map<Long, UserSummary> actors = userLookupService.summaries(logs.stream()
                .map(AuditLog::getUserId).filter(Objects::nonNull).collect(Collectors.toSet()));
        return PageResponse.from(logs.map(log -> auditLogMapper.toResponse(log, actors.get(log.getUserId()))));
    }

    @Transactional(readOnly = true)
    public AuditLogResponse get(UUID publicId) {
        AuditLog log = auditLogRepository.findByPublicId(publicId).orElseThrow(() -> NotFoundException.of("Audit log"));
        UserSummary actor = log.getUserId() == null
                ? null : userLookupService.summaries(Set.of(log.getUserId())).get(log.getUserId());
        return auditLogMapper.toResponse(log, actor);
    }
}
