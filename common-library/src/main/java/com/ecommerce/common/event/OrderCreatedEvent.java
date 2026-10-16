package com.ecommerce.common.event;

import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreatedEvent implements DomainEvent {
    @Builder.Default
    private UUID eventId = UUID.randomUUID();
    @Builder.Default
    private String eventType = "order.created";
    @Builder.Default
    private int eventVersion = 1;
    @Builder.Default
    private Instant occurredAt = Instant.now();
    private String correlationId;
    @Builder.Default
    private String producer = "order-service";

    // Business Payload
    private UUID orderId;
    private UUID customerId;
    private String orderCode;
    private List<OrderItemPayload> items;
    private BigDecimal totalAmount;
    private String currency;
    private Instant reservationExpiresAt;
}
