package com.example.orderservice.controller;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.exception.GlobalExceptionHandler;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.example.orderservice.dto.InternalOrderNotificationResponse;
import com.example.orderservice.dto.InternalOrderPaymentResponse;
import com.example.orderservice.dto.NotificationOrderItemResponse;
import com.example.orderservice.dto.NotificationShippingAddressResponse;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.security.AccessDenied;
import com.example.orderservice.security.JwtEntryPoint;
import com.example.orderservice.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = InternalOrderController.class)
@Import({GlobalExceptionHandler.class, AccessDenied.class, JwtEntryPoint.class, InternalOrderControllerSecurityTests.MethodSecurityTestConfig.class})
class InternalOrderControllerSecurityTests {

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Positive: Payment service with ROLE_SERVICE_PAYMENT accesses internal order details without PII")
    @WithMockUser(username = "payment-service", roles = {"SERVICE_PAYMENT"})
    void testGetInternalOrder_PaymentServiceAuthorized() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        InternalOrderPaymentResponse response = InternalOrderPaymentResponse.builder()
                .orderId(orderId)
                .customerId(customerId)
                .totalAmount(new BigDecimal("150000"))
                .currency("VND")
                .status("PENDING")
                .orderCode("ORD-123")
                .build();

        when(orderService.getInternalOrderForPayment(orderId)).thenReturn(response);

        mockMvc.perform(get("/internal/orders/{orderId}", orderId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.data.totalAmount").value(150000))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.shippingAddress").doesNotExist())
                .andExpect(jsonPath("$.data.contactInfo").doesNotExist());
    }

    @Test
    @DisplayName("Negative: Caller with ROLE_CUSTOMER is denied access with 403")
    @WithMockUser(username = "customer-1", roles = {"CUSTOMER"})
    void testGetInternalOrder_CustomerRoleForbidden() throws Exception {
        UUID orderId = UUID.randomUUID();
        mockMvc.perform(get("/internal/orders/{orderId}", orderId))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Negative: Caller with ROLE_SERVICE_ORDER is denied access with 403")
    @WithMockUser(username = "order-service", roles = {"SERVICE_ORDER"})
    void testGetInternalOrder_WrongServiceRoleForbidden() throws Exception {
        UUID orderId = UUID.randomUUID();
        mockMvc.perform(get("/internal/orders/{orderId}", orderId))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Negative: Unauthenticated request to /internal/orders/{orderId} returns 401")
    void testGetInternalOrder_UnauthenticatedReturns401() throws Exception {
        UUID orderId = UUID.randomUUID();
        mockMvc.perform(get("/internal/orders/{orderId}", orderId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Negative: Non-existent order returns 404")
    @WithMockUser(username = "payment-service", roles = {"SERVICE_PAYMENT"})
    void testGetInternalOrder_NotFoundReturns404() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(orderService.getInternalOrderForPayment(orderId))
                .thenThrow(new ResourceNotFoundException("Đơn hàng không tồn tại"));

        mockMvc.perform(get("/internal/orders/{orderId}", orderId))
                .andExpect(status().isNotFound());
    }

    // --- Tests for GET /internal/orders/{orderId}/notification-snapshot ---

    @Test
    @DisplayName("POS-NOTIF-01: Notification service with ROLE_SERVICE_NOTIFICATION accesses notification snapshot")
    @WithMockUser(username = "notification-service", roles = {"SERVICE_NOTIFICATION"})
    void testGetNotificationSnapshot_NotificationServiceAuthorized() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();

        NotificationShippingAddressResponse shipping = NotificationShippingAddressResponse.builder()
                .recipientName("Nguyễn Văn A")
                .addressLine1("123 Lê Duẩn")
                .district("Hải Châu")
                .city("Đà Nẵng")
                .country("Việt Nam")
                .formattedAddress("123 Lê Duẩn, Hải Châu, Đà Nẵng, Việt Nam")
                .build();

        NotificationOrderItemResponse item = NotificationOrderItemResponse.builder()
                .productName("iPhone 15 Pro")
                .skuName("Đen 128GB")
                .quantity(2)
                .unitPrice(new BigDecimal("25000000"))
                .lineTotal(new BigDecimal("50000000"))
                .build();

        InternalOrderNotificationResponse response = InternalOrderNotificationResponse.builder()
                .orderId(orderId)
                .customerId(customerId)
                .orderCode("ORD-2026-999")
                .status(OrderStatus.CONFIRMED)
                .totalAmount(new BigDecimal("50000000"))
                .currency("VND")
                .createdAt(Instant.parse("2026-10-26T10:00:00Z"))
                .shippingAddress(shipping)
                .items(List.of(item))
                .build();

        when(orderService.getInternalOrderForNotification(orderId)).thenReturn(response);

        mockMvc.perform(get("/internal/orders/{orderId}/notification-snapshot", orderId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.data.customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.data.orderCode").value("ORD-2026-999"))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.totalAmount").value(50000000))
                .andExpect(jsonPath("$.data.currency").value("VND"))
                .andExpect(jsonPath("$.data.createdAt").value("2026-10-26T10:00:00Z"))
                .andExpect(jsonPath("$.data.shippingAddress.recipientName").value("Nguyễn Văn A"))
                .andExpect(jsonPath("$.data.shippingAddress.formattedAddress").value("123 Lê Duẩn, Hải Châu, Đà Nẵng, Việt Nam"))
                .andExpect(jsonPath("$.data.shippingAddress.phone").doesNotExist())
                .andExpect(jsonPath("$.data.paymentMethod").doesNotExist())
                .andExpect(jsonPath("$.data.subtotal").doesNotExist())
                .andExpect(jsonPath("$.data.discount").doesNotExist())
                .andExpect(jsonPath("$.data.shippingFee").doesNotExist())
                .andExpect(jsonPath("$.data.cancelReason").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].productName").value("iPhone 15 Pro"))
                .andExpect(jsonPath("$.data.items[0].skuName").value("Đen 128GB"))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2))
                .andExpect(jsonPath("$.data.items[0].unitPrice").value(25000000))
                .andExpect(jsonPath("$.data.items[0].lineTotal").value(50000000));
    }

    @Test
    @DisplayName("NEG-NOTIF-01: Payment service with ROLE_SERVICE_PAYMENT is denied access to notification snapshot with 403")
    @WithMockUser(username = "payment-service", roles = {"SERVICE_PAYMENT"})
    void testGetNotificationSnapshot_PaymentServiceForbidden() throws Exception {
        UUID orderId = UUID.randomUUID();
        mockMvc.perform(get("/internal/orders/{orderId}/notification-snapshot", orderId))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("NEG-NOTIF-02: Order service with ROLE_SERVICE_ORDER is denied access to notification snapshot with 403")
    @WithMockUser(username = "order-service", roles = {"SERVICE_ORDER"})
    void testGetNotificationSnapshot_OrderServiceRoleForbidden() throws Exception {
        UUID orderId = UUID.randomUUID();
        mockMvc.perform(get("/internal/orders/{orderId}/notification-snapshot", orderId))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("NEG-NOTIF-03: Customer role is denied access to notification snapshot with 403")
    @WithMockUser(username = "customer-1", roles = {"CUSTOMER"})
    void testGetNotificationSnapshot_CustomerRoleForbidden() throws Exception {
        UUID orderId = UUID.randomUUID();
        mockMvc.perform(get("/internal/orders/{orderId}/notification-snapshot", orderId))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("NEG-NOTIF-04: Unauthenticated request to notification snapshot returns 401")
    void testGetNotificationSnapshot_UnauthenticatedReturns401() throws Exception {
        UUID orderId = UUID.randomUUID();
        mockMvc.perform(get("/internal/orders/{orderId}/notification-snapshot", orderId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("NEG-NOTIF-05: Non-existent order on notification snapshot returns 404")
    @WithMockUser(username = "notification-service", roles = {"SERVICE_NOTIFICATION"})
    void testGetNotificationSnapshot_NotFoundReturns404() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(orderService.getInternalOrderForNotification(orderId))
                .thenThrow(new ResourceNotFoundException("Đơn hàng không tồn tại"));

        mockMvc.perform(get("/internal/orders/{orderId}/notification-snapshot", orderId))
                .andExpect(status().isNotFound());
    }
}
