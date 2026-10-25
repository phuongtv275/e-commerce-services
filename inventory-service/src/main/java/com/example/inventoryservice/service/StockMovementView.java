package com.example.inventoryservice.service;

import java.time.Instant;
import java.util.UUID;

public record StockMovementView(
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
) {}
