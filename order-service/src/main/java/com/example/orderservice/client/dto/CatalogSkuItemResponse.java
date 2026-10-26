package com.example.orderservice.client.dto;

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
public class CatalogSkuItemResponse {
    private UUID skuId;
    private String skuCode;
    private UUID productId;
    private String productName;
    private String skuName;
    private String attributes;
    private BigDecimal price;
    private String currency;
    private String status;
}
