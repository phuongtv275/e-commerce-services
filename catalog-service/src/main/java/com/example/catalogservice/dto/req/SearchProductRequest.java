package com.example.catalogservice.dto.req;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchProductRequest {

    private String q;

    private UUID categoryId;

    private UUID brandId;

    private BigDecimal minPrice;

    private BigDecimal maxPrice;

    private Boolean inStock;

    @Builder.Default
    private String sort = "relevance";

    @Builder.Default
    @Min(value = 0, message = "Page index must not be negative")
    private int page = 0;

    @Builder.Default
    @Min(value = 1, message = "Page size must be at least 1")
    @Max(value = 100, message = "Page size must not exceed 100")
    private int size = 20;

    @Builder.Default
    private String mode = "offset";

    private String cursor;

    private Map<String, String> attributes;
}
