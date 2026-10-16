package com.ecommerce.common.event;

import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PasswordResetRequestedEvent implements DomainEvent {
    @Builder.Default
    private UUID eventId = UUID.randomUUID();
    @Builder.Default
    private String eventType = "user.password-reset-requested";
    @Builder.Default
    private int eventVersion = 1;
    @Builder.Default
    private Instant occurredAt = Instant.now();
    private String correlationId;
    @Builder.Default
    private String producer = "identity-service";

    // Business Payload
    private UUID userId;
    private String email;
    private String resetToken;
    private Instant expiresAt;
}
