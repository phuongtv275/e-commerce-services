package com.example.orderservice.controller;

import com.ecommerce.common.response.ApiResponse;
import com.ecommerce.common.response.PagedResponse;
import com.example.orderservice.dto.*;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.security.SecurityUtils;
import com.example.orderservice.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping("/checkout")
    public ResponseEntity<ApiResponse<CheckoutResponse>> checkout(
            @RequestHeader(value = "Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CheckoutRequest request) {

        UUID customerId = SecurityUtils.getRequiredCurrentCustomerId();
        CheckoutResponse response = orderService.checkout(customerId, idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<CustomerOrderResponse>>> getOrders(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        UUID customerId = SecurityUtils.getRequiredCurrentCustomerId();
        int safeSize = Math.max(1, Math.min(100, size));
        int safePage = Math.max(0, page);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt", "id"));

        PagedResponse<CustomerOrderResponse> response = orderService.getOrders(customerId, status, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponse<CustomerOrderDetailResponse>> getOrderDetail(@PathVariable UUID orderId) {
        UUID customerId = SecurityUtils.getRequiredCurrentCustomerId();
        CustomerOrderDetailResponse response = orderService.getOrderDetail(customerId, orderId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<ApiResponse<CustomerOrderDetailResponse>> cancelOrder(
            @PathVariable UUID orderId,
            @RequestBody(required = false) CancelOrderRequest request) {

        UUID customerId = SecurityUtils.getRequiredCurrentCustomerId();
        CustomerOrderDetailResponse response = orderService.cancelOrder(customerId, orderId, request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
