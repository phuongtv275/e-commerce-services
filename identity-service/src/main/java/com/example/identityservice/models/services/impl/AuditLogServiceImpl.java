package com.example.identityservice.models.services.impl;

import com.example.identityservice.models.dto.res.AuditLogRes;
import com.example.identityservice.models.entities.AuditLog;
import com.example.identityservice.models.repositories.AuditLogRepository;
import com.example.identityservice.models.services.AuditLogService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogServiceImpl implements AuditLogService {

    private final AuditLogRepository auditLogRepository;

    @Override
    @Transactional
    public void logAction(UUID actorId, String actorEmail, String action, String targetType, String targetId, String delta, String ipAddress, String correlationId) {
        AuditLog auditLog = AuditLog.builder()
                .actorId(actorId)
                .actorEmail(actorEmail)
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .delta(delta)
                .ipAddress(ipAddress)
                .correlationId(correlationId)
                .build();
        auditLogRepository.save(auditLog);
        log.debug("Recorded audit log: action={}, actorEmail={}, correlationId={}", action, actorEmail, correlationId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditLogRes> getAuditLogs(UUID actorId, String action, Instant from, Instant to, Pageable pageable) {
        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (actorId != null) {
                predicates.add(cb.equal(root.get("actorId"), actorId));
            }
            if (action != null && !action.isBlank()) {
                predicates.add(cb.equal(root.get("action"), action));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("occurredAt"), to));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return auditLogRepository.findAll(spec, pageable).map(logEntity -> AuditLogRes.builder()
                .id(logEntity.getId())
                .actorId(logEntity.getActorId())
                .actorEmail(logEntity.getActorEmail())
                .action(logEntity.getAction())
                .targetType(logEntity.getTargetType())
                .targetId(logEntity.getTargetId())
                .delta(logEntity.getDelta())
                .ipAddress(logEntity.getIpAddress())
                .correlationId(logEntity.getCorrelationId())
                .occurredAt(logEntity.getOccurredAt())
                .build());
    }
}
