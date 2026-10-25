package com.example.orderservice.controller;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.exception.GlobalExceptionHandler;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.example.orderservice.dto.InternalOrderPaymentResponse;
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
}
