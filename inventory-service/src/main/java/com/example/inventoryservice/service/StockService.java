package com.example.inventoryservice.service;

import com.example.inventoryservice.dto.BatchStockResponse;
import com.example.inventoryservice.entity.MovementType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface StockService {
    StockBalance adjust(StockAdjustmentCommand command, String actor, String correlationId);
    StockBalance get(UUID skuId, UUID warehouseId);
    BatchStockResponse getBatchStockAvailability(List<UUID> skuIds);
    Page<StockMovementView> movements(UUID skuId, UUID warehouseId, Pageable pageable);
    Page<StockMovementView> movements(UUID skuId, UUID warehouseId, Instant from, Instant to, MovementType movementType, Pageable pageable);
}
