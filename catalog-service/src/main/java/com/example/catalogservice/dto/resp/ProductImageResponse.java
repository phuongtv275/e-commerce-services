package com.example.catalogservice.dto.resp;

import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductImageResponse {
    private UUID id;
    private UUID productId;
    private UUID skuId;
    private String imageUrl;
    private String altText;
    private boolean primary;
    private int displayOrder;
    private Instant createdAt;
}
