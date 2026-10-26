package com.ecommerce.common.event;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserRegisteredEvent implements DomainEvent {
    @Builder.Default
    private UUID eventId = UUID.randomUUID();
    @Builder.Default
    private String eventType = "user.registered";
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
    private String fullName;
    @JsonAlias({"token"})
    private String verificationToken;
}
