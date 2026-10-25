package com.example.inventoryservice.service;

import java.time.Instant;
import java.util.UUID;

public record StockBalance(
        UUID skuId,
        UUID warehouseId,
        int onHand,
        int reserved,
        int available,
        long version,
        Instant updatedAt
) {}
