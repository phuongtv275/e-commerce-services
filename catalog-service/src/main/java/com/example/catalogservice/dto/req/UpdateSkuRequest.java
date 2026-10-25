package com.example.catalogservice.dto.req;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateSkuRequest {

    @NotBlank(message = "SKU code is required")
    @Size(max = 100, message = "SKU code must not exceed 100 characters")
    private String skuCode;

    @NotBlank(message = "SKU name is required")
    @Size(max = 500, message = "SKU name must not exceed 500 characters")
    private String name;

    @Builder.Default
    private String attributes = "{}";

    @NotNull(message = "Price is required")
    @DecimalMin(value = "0.0", message = "Price must be non-negative")
    private BigDecimal price;

    @Builder.Default
    private String currency = "VND";
}
