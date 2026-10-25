package com.example.orderservice.controller;

import com.ecommerce.common.exception.GlobalExceptionHandler;
import com.ecommerce.common.response.ApiResponse;
import com.example.orderservice.dto.*;
import com.example.orderservice.security.AccessDenied;
import com.example.orderservice.security.JwtEntryPoint;
import com.example.orderservice.service.CartService;
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
import java.util.Collections;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = CartController.class)
@Import({GlobalExceptionHandler.class, JwtEntryPoint.class, AccessDenied.class})
class CartControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CartService cartService;

    @Autowired
    private ObjectMapper objectMapper;

    private final UUID customerId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    @DisplayName("Positive: GET /api/v1/orders/cart returns 200 with cart response")
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111", roles = {"CUSTOMER"})
    void testGetCart_Success() throws Exception {
        CartResponse cartResponse = CartResponse.builder()
                .cartId(UUID.randomUUID())
                .items(Collections.emptyList())
                .subtotal(BigDecimal.ZERO)
                .warnings(Collections.emptyList())
                .build();

        when(cartService.getCart(customerId)).thenReturn(cartResponse);

        mockMvc.perform(get("/api/v1/orders/cart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    @Test
    @DisplayName("Positive: POST /api/v1/orders/cart/items adds item and returns 200")
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111", roles = {"CUSTOMER"})
    void testAddToCart_Success() throws Exception {
        AddToCartRequest req = new AddToCartRequest(UUID.randomUUID(), 2);
        CartResponse cartResponse = CartResponse.builder()
                .cartId(UUID.randomUUID())
                .items(Collections.emptyList())
                .subtotal(new BigDecimal("100000"))
                .build();

        when(cartService.addToCart(eq(customerId), any())).thenReturn(cartResponse);

        mockMvc.perform(post("/api/v1/orders/cart/items")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    @Test
    @DisplayName("Negative: Unauthenticated cart access returns 401")
    void testGetCart_Unauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/orders/cart"))
                .andExpect(status().isUnauthorized());
    }
}
