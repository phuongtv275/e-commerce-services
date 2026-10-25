package com.example.catalogservice.service;

import com.example.catalogservice.entity.CatalogAuditLog;
import com.example.catalogservice.repository.CatalogAuditLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final CatalogAuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String actorId, String actorType, String action, String entityType, String entityId, Object before, Object after) {
        try {
            JsonNode beforeNode = before != null ? objectMapper.valueToTree(before) : null;
            JsonNode afterNode = after != null ? objectMapper.valueToTree(after) : null;
            String correlationId = MDC.get("correlationId");

            CatalogAuditLog auditLog = CatalogAuditLog.builder()
                    .actorId(actorId)
                    .actorType(actorType != null ? actorType : "USER")
                    .action(action)
                    .entityType(entityType)
                    .entityId(entityId)
                    .beforeState(beforeNode)
                    .afterState(afterNode)
                    .correlationId(correlationId)
                    .build();

            auditLogRepository.save(auditLog);
        } catch (Exception e) {
            log.error("Failed to record audit log for action {} on {} {}: {}", action, entityType, entityId, e.getMessage(), e);
        }
    }
}
