package com.ecommerce.common.event;

import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentLateSuccessEvent implements DomainEvent {
    @Builder.Default
    private UUID eventId = UUID.randomUUID();
    @Builder.Default
    private String eventType = "payment.late-success";
    @Builder.Default
    private int eventVersion = 1;
    @Builder.Default
    private Instant occurredAt = Instant.now();
    private String correlationId;
    @Builder.Default
    private String producer = "payment-service";

    // Business Payload
    private UUID paymentId;
    private UUID orderId;
    private BigDecimal amount;
    private String currency;
    private String providerPaymentId;
    private String lateSuccessReason; // e.g. "RESERVATION_EXPIRED_ORDER_CANCELLED"
}
