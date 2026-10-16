package com.ecommerce.common.event;

import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryReleasedEvent implements DomainEvent {
    @Builder.Default
    private UUID eventId = UUID.randomUUID();
    @Builder.Default
    private String eventType = "inventory.released";
    @Builder.Default
    private int eventVersion = 1;
    @Builder.Default
    private Instant occurredAt = Instant.now();
    private String correlationId;
    @Builder.Default
    private String producer = "inventory-service";

    // Business Payload
    private UUID orderId;
    private UUID reservationId;
    private String reason;
}
