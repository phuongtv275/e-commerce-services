package com.example.inventoryservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record StockAdjustmentRequest(
        UUID warehouseId,

        @NotNull(message = "quantityDelta is required")
        Integer quantityDelta,

        @NotBlank(message = "referenceId is required")
        String referenceId,

        @NotBlank(message = "reason is required")
        @Size(max = 500, message = "reason must not exceed 500 characters")
        String reason
) {}
