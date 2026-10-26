package com.example.orderservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartResponse {
    private UUID cartId;
    @Builder.Default
    private List<CartItemResponse> items = new ArrayList<>();
    private BigDecimal subtotal;
    @Builder.Default
    private List<CartWarningResponse> warnings = new ArrayList<>();
}
