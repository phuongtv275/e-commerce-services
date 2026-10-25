package com.example.inventoryservice.dto;

import com.example.inventoryservice.service.StockMovementView;

import java.time.Instant;
import java.util.UUID;

public record StockMovementResponse(
        UUID id,
        UUID skuId,
        UUID warehouseId,
        String movementType,
        int quantityDelta,
        String referenceType,
        String referenceId,
        int beforeOnHand,
        int afterOnHand,
        int beforeReserved,
        int afterReserved,
        String actor,
        String reason,
        String correlationId,
        Instant occurredAt
) {
    public static StockMovementResponse from(StockMovementView v) {
        return new StockMovementResponse(
                v.id(),
                v.skuId(),
                v.warehouseId(),
                v.movementType(),
                v.quantityDelta(),
                v.referenceType(),
                v.referenceId(),
                v.beforeOnHand(),
                v.afterOnHand(),
                v.beforeReserved(),
                v.afterReserved(),
                v.actor(),
                v.reason(),
                v.correlationId(),
                v.occurredAt()
        );
    }
}
