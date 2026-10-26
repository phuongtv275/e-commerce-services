package com.example.orderservice.controller;

import com.ecommerce.common.response.ApiResponse;
import com.example.orderservice.dto.InternalOrderNotificationResponse;
import com.example.orderservice.dto.InternalOrderPaymentResponse;
import com.example.orderservice.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/internal/orders")
@RequiredArgsConstructor
public class InternalOrderController {

    private final OrderService orderService;

    @GetMapping("/{orderId}")
    @PreAuthorize("hasRole('SERVICE_PAYMENT')")
    public ResponseEntity<ApiResponse<InternalOrderPaymentResponse>> getInternalOrderForPayment(@PathVariable UUID orderId) {
        InternalOrderPaymentResponse response = orderService.getInternalOrderForPayment(orderId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{orderId}/notification-snapshot")
    @PreAuthorize("hasRole('SERVICE_NOTIFICATION')")
    public ResponseEntity<ApiResponse<InternalOrderNotificationResponse>> getInternalOrderForNotification(@PathVariable UUID orderId) {
        InternalOrderNotificationResponse response = orderService.getInternalOrderForNotification(orderId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
