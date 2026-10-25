package com.example.catalogservice.dto.resp;

import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductSearchItem {

    private UUID skuId;

    private String skuCode;

    private UUID productId;

    private String productName;

    private String name;

    private String description;

    private UUID categoryId;

    private String categoryName;

    private UUID brandId;

    private String brandName;

    private Double price;

    private String currency;

    private Map<String, Object> attributes;

    private List<ProductImageResponse> images;

    private Boolean inStock;

    private Instant updatedAt;
}
