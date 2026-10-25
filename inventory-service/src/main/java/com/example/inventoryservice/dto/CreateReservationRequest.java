package com.example.inventoryservice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreateReservationRequest(
        @NotNull(message = "orderId is required")
        UUID orderId,

        @NotEmpty(message = "items cannot be empty")
        @Size(max = 100, message = "items cannot exceed 100")
        @Valid
        List<ReservationItemRequest> items
) {}
