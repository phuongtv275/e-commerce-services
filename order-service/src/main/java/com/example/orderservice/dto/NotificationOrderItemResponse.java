package com.example.orderservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationOrderItemResponse {
    private String productName;
    private String skuName;
    private int quantity;
    private BigDecimal unitPrice;
    private BigDecimal lineTotal;
}
