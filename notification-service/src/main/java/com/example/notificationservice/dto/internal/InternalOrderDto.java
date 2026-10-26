package com.example.notificationservice.dto.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class InternalOrderDto {
    private UUID orderId;
    private UUID customerId;
    private String orderCode;
    private String status;
    private BigDecimal totalAmount;
    private String currency;
    private String shippingAddress;
    private List<InternalOrderItemDto> items;
}
