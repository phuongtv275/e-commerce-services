package com.ecommerce.common.event;

import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryReservedEvent implements DomainEvent {
    @Builder.Default
    private UUID eventId = UUID.randomUUID();
    @Builder.Default
    private String eventType = "inventory.reserved";
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
    private List<ReservationItemPayload> items;
    private Instant expiresAt;
}
