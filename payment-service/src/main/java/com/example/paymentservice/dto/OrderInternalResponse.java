package com.example.paymentservice.dto;

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
public class OrderInternalResponse {

    private UUID orderId;
    private UUID customerId;
    private BigDecimal totalAmount;
    private String currency;
    private String status;
}
