package com.example.catalogservice.dto.resp;

import com.example.catalogservice.entity.ProductStatus;
import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductResponse {
    private UUID id;
    private String name;
    private String description;
    private String metadata;
    private UUID categoryId;
    private String categoryName;
    private UUID brandId;
    private String brandName;
    private ProductStatus status;
    private List<SkuResponse> skus;
    private List<ProductImageResponse> images;
    private Instant createdAt;
    private Instant updatedAt;
}
