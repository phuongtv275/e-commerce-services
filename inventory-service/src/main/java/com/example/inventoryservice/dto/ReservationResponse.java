package com.example.inventoryservice.dto;

import com.example.inventoryservice.service.ReservationResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReservationResponse(
        UUID reservationId,
        UUID orderId,
        String status,
        Instant expiresAt,
        List<ReservationItemResponse> items
) {
    public static ReservationResponse from(ReservationResult r) {
        List<ReservationItemResponse> itemResponses = r.items().stream()
                .map(i -> new ReservationItemResponse(i.skuId(), i.quantity()))
                .toList();
        return new ReservationResponse(
                r.reservationId(),
                r.orderId(),
                r.status(),
                r.expiresAt(),
                itemResponses
        );
    }
}
