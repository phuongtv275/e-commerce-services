package com.example.orderservice.dto;

import com.example.orderservice.entity.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerOrderResponse {
    private UUID orderId;
    private String orderCode;
    private OrderStatus status;
    private BigDecimal totalAmount;
    private String currency;
    private int totalItems;
    private Instant createdAt;
}
