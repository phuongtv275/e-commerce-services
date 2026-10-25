package com.example.inventoryservice.dto;

import com.example.inventoryservice.service.StockBalance;

import java.time.Instant;
import java.util.UUID;

public record StockBalanceResponse(
        UUID skuId,
        UUID warehouseId,
        int onHand,
        int reserved,
        int available,
        long version,
        Instant updatedAt
) {
    public static StockBalanceResponse from(StockBalance b) {
        return new StockBalanceResponse(
                b.skuId(),
                b.warehouseId(),
                b.onHand(),
                b.reserved(),
                b.available(),
                b.version(),
                b.updatedAt()
        );
    }
}
