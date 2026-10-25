package com.example.inventoryservice.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ReservationService {
    ReservationResult reserve(UUID orderId, List<ReservationItemCommand> items, String correlationId);
    ReservationResult release(UUID orderId, String reason, String correlationId);
    void commit(UUID orderId, String correlationId);
    void releaseForEvent(UUID orderId, String reason, String correlationId);
    void restockForEvent(UUID orderId, String reason, String correlationId);
    int expireDue(Instant now, int batchSize);
    void quarantineCommitFailure(UUID orderId, String reason, String payloadJson, String correlationId);
}
