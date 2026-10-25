package com.example.orderservice.client.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryStockBatchResponse {
    @Builder.Default
    private List<InventoryStockItemResponse> items = new ArrayList<>();
    @Builder.Default
    private List<UUID> missingSkuIds = new ArrayList<>();
}
