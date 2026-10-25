package com.example.inventoryservice.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record BatchStockRequest(
        @NotEmpty(message = "skuIds list cannot be empty")
        @Size(max = 100, message = "skuIds list cannot exceed 100 IDs")
        List<@NotNull(message = "skuId cannot be null") UUID> skuIds
) {}
