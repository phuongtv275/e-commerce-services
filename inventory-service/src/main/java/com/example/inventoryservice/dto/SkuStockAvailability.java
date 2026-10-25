package com.example.inventoryservice.dto;

import lombok.Builder;

import java.util.UUID;

@Builder
public record SkuStockAvailability(
        UUID skuId,
        int availableQuantity
) {}
