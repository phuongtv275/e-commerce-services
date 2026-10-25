package com.example.inventoryservice.service;

import java.util.UUID;

public record StockAdjustmentCommand(
        UUID skuId,
        UUID warehouseId,
        int quantityDelta,
        String referenceId,
        String reason
) {}
