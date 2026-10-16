package com.ecommerce.common.event;

import java.time.Instant;
import java.util.UUID;

public interface DomainEvent {
    UUID getEventId();
    String getEventType();
    int getEventVersion();
    Instant getOccurredAt();
    String getCorrelationId();
    String getProducer();
}
