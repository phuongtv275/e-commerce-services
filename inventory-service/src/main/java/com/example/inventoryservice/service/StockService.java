package com.example.inventoryservice.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface StockService {
    StockBalance adjust(StockAdjustmentCommand command, String actor, String correlationId);
    StockBalance get(UUID skuId, UUID warehouseId);
    Page<StockMovementView> movements(UUID skuId, UUID warehouseId, Pageable pageable);
}
