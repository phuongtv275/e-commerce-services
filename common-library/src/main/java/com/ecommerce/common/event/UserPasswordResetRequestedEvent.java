package com.ecommerce.common.event;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserPasswordResetRequestedEvent(
    UUID eventId,
    String eventType,
    int eventVersion,
    Instant occurredAt,
    String correlationId,
    String producer,
    UUID userId,
    String email,
    String fullName,
    @JsonAlias({"token"})
    String resetToken
) {}
