package com.example.inventoryservice.dto;

import lombok.Builder;

import java.util.List;
import java.util.UUID;

@Builder
public record BatchStockResponse(
        List<SkuStockAvailability> items,
        List<UUID> missingSkuIds
) {}
