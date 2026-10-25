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
public class OrderItemDetailResponse {
    private UUID itemId;
    private UUID skuId;
    private String skuCode;
    private String productName;
    private String skuName;
    private String attributes;
    private BigDecimal unitPrice;
    private int quantity;
    private BigDecimal lineTotal;
}
