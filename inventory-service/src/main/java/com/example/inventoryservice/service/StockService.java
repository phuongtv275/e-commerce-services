package com.example.inventoryservice.service;

import com.example.inventoryservice.entity.MovementType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.UUID;

public interface StockService {
    StockBalance adjust(StockAdjustmentCommand command, String actor, String correlationId);
    StockBalance get(UUID skuId, UUID warehouseId);
    Page<StockMovementView> movements(UUID skuId, UUID warehouseId, Pageable pageable);
    Page<StockMovementView> movements(UUID skuId, UUID warehouseId, Instant from, Instant to, MovementType movementType, Pageable pageable);
}
