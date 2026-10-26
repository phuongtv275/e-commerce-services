package com.example.orderservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderStatusHistoryDetailResponse {
    private UUID historyId;
    private String oldStatus;
    private String newStatus;
    private String reason;
    private String actor;
    private Instant occurredAt;
}
