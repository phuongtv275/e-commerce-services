package com.example.orderservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InternalOrderPaymentResponse {
    private UUID orderId;
    private UUID customerId;
    private BigDecimal totalAmount;
    private String currency;
    private String status;
    private String orderCode;
}
