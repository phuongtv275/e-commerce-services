package com.example.orderservice.service;

import com.ecommerce.common.response.PagedResponse;
import com.example.orderservice.dto.*;
import com.example.orderservice.entity.OrderStatus;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface OrderService {

    CheckoutResponse checkout(UUID customerId, String idempotencyKey, CheckoutRequest request);

    CustomerOrderDetailResponse getOrderDetail(UUID customerId, UUID orderId);

    PagedResponse<CustomerOrderResponse> getOrders(UUID customerId, OrderStatus status, Pageable pageable);

    CustomerOrderDetailResponse cancelOrder(UUID customerId, UUID orderId, CancelOrderRequest request);

    InternalOrderPaymentResponse getInternalOrderForPayment(UUID orderId);

    void handlePaymentSucceeded(UUID orderId, UUID paymentId);

    void handlePaymentFailed(UUID orderId, String reason);

    void handleReservationExpired(UUID orderId);

    void handleReservationCommitFailed(UUID orderId, String reason);
}
