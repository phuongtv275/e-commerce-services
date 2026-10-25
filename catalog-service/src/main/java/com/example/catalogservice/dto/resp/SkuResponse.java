package com.example.catalogservice.dto.resp;

import com.example.catalogservice.entity.SkuStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkuResponse {
    private UUID id;
    private UUID productId;
    private String skuCode;
    private String name;
    private String attributes;
    private BigDecimal price;
    private String currency;
    private SkuStatus status;
    private Instant createdAt;
    private Instant updatedAt;
}
