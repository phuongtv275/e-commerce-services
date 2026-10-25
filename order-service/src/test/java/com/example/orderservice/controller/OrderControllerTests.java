package com.example.orderservice.controller;

import com.ecommerce.common.exception.GlobalExceptionHandler;
import com.ecommerce.common.response.ApiResponse;
import com.example.orderservice.dto.*;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.security.AccessDenied;
import com.example.orderservice.security.JwtEntryPoint;
import com.example.orderservice.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = OrderController.class)
@Import({GlobalExceptionHandler.class, JwtEntryPoint.class, AccessDenied.class})
class OrderControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @Autowired
    private ObjectMapper objectMapper;

    private final UUID customerId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    @DisplayName("Positive: POST /api/v1/orders/checkout with Idempotency-Key returns 201 Created")
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111", roles = {"CUSTOMER"})
    void testCheckout_Success() throws Exception {
        UUID addressId = UUID.randomUUID();
        CheckoutRequest req = new CheckoutRequest(addressId, "VNPAY");
        UUID orderId = UUID.randomUUID();

        CheckoutResponse response = CheckoutResponse.builder()
                .orderId(orderId)
                .orderCode("ORD-123456")
                .status(OrderStatus.PENDING)
                .totalAmount(new BigDecimal("200000"))
                .currency("VND")
                .createdAt(Instant.now())
                .build();

        when(orderService.checkout(eq(customerId), eq("key-123"), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/orders/checkout")
                        .with(csrf())
                        .header("Idempotency-Key", "key-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    @DisplayName("Negative: POST /api/v1/orders/checkout without Idempotency-Key returns 400 Bad Request")
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111", roles = {"CUSTOMER"})
    void testCheckout_MissingIdempotencyKey() throws Exception {
        CheckoutRequest req = new CheckoutRequest(UUID.randomUUID(), "VNPAY");

        mockMvc.perform(post("/api/v1/orders/checkout")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Negative: GET /api/v1/orders/{orderId} unauthenticated returns 401")
    void testGetOrderDetail_Unauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/orders/{orderId}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
