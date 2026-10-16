package com.example.identityservice.models.services;

import com.example.identityservice.models.dto.res.AuditLogRes;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.UUID;

public interface AuditLogService {

    void logAction(UUID actorId, String actorEmail, String action, String targetType, String targetId, String delta, String ipAddress, String correlationId);

    Page<AuditLogRes> getAuditLogs(UUID actorId, String action, Instant from, Instant to, Pageable pageable);
}
