package com.ecommerce.common.event;

import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderItemPayload {
    private UUID skuId;
    private String skuCode;
    private int quantity;
    private BigDecimal unitPrice;
}
