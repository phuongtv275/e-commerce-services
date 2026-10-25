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
public class CustomerOrderDetailResponse {
    private UUID orderId;
    private String orderCode;
    private OrderStatus status;
    private BigDecimal subtotal;
    private BigDecimal discount;
    private BigDecimal tax;
    private BigDecimal shippingFee;
    private BigDecimal totalAmount;
    private String currency;
    private String shippingAddress;
    private String contactInfo;
    private String paymentMethod;
    private String cancelReason;
    private Instant cancelledAt;
    private Instant createdAt;
    @Builder.Default
    private List<OrderItemDetailResponse> items = new ArrayList<>();
    @Builder.Default
    private List<OrderStatusHistoryDetailResponse> statusHistories = new ArrayList<>();
}
