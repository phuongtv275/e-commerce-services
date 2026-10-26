package com.example.orderservice.dto;

import com.example.orderservice.entity.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InternalOrderNotificationResponse {
    private UUID orderId;
    private UUID customerId;
    private String orderCode;
    private OrderStatus status;
    private BigDecimal totalAmount;
    private String currency;
    private Instant createdAt;

    private NotificationShippingAddressResponse shippingAddress;

    @Builder.Default
    private List<NotificationOrderItemResponse> items = new ArrayList<>();
}
