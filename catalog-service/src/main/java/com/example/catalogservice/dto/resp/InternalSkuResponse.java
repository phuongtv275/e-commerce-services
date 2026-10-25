package com.example.catalogservice.dto.resp;

import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InternalSkuResponse {
    private UUID skuId;
    private String skuCode;
    private UUID productId;
    private String productName;
    private String skuName;
    private String attributes;
    private BigDecimal price;
    private String currency;
    private String status;
    private Instant updatedAt;
}
