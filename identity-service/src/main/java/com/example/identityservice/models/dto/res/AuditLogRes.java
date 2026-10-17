package com.example.identityservice.models.dto.res;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record AuditLogRes(
        UUID id,
        UUID actorId,
        String actorEmail,
        String action,
        String targetType,
        String targetId,
        String delta,
        String ipAddress,
        String correlationId,
        Instant occurredAt
) {}
