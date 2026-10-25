package com.example.inventoryservice.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReservationResult(
        UUID reservationId,
        UUID orderId,
        String status,
        Instant expiresAt,
        List<ReservationItemCommand> items
) {}
