package com.example.paymentservice.controller;

import com.ecommerce.common.exception.ResourceNotFoundException;
import com.example.paymentservice.dto.PaymentInitiateRequest;
import com.example.paymentservice.dto.PaymentInitiateResponse;
import com.example.paymentservice.dto.PaymentResponse;
import com.example.paymentservice.entity.PaymentStatus;
import com.example.paymentservice.provider.vnpay.VnPayIpnResponse;
import com.example.paymentservice.service.PaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:payment_db;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "vnpay.tmn-code=TESTTMN",
        "vnpay.hash-secret=SECRET12345678901234567890123456789012",
        "vnpay.ip-address=103.1.2.3",
        "vnpay.return-url=https://example.com/return",
        "vnpay.ipn-url=https://example.com/ipn"
})
class PaymentControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PaymentService paymentService;

    @Test
    @DisplayName("Positive: GET /api/v1/payments/vnpay/ipn is public (JWT bypassed) and returns HTTP 200 JSON")
    void testGetIpnPublicBypassesJwt() throws Exception {
        when(paymentService.handleIpnCallback(anyMap()))
                .thenReturn(VnPayIpnResponse.success());

        mockMvc.perform(get("/api/v1/payments/vnpay/ipn")
                        .param("vnp_Amount", "10000000")
                        .param("vnp_TxnRef", "TXN123")
                        .param("vnp_SecureHash", "HASH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.RspCode").value("00"))
                .andExpect(jsonPath("$.Message").value("Confirm Success"));
    }

    @Test
    @DisplayName("Negative: POST /api/v1/payments/initiate without authentication returns 401")
    void testInitiatePaymentUnauthenticatedReturns401() throws Exception {
        PaymentInitiateRequest request = new PaymentInitiateRequest(UUID.randomUUID());

        mockMvc.perform(post("/api/v1/payments/initiate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Positive: POST /api/v1/payments/initiate extracts X-Trusted-Client-IP and ignores X-Forwarded-For")
    void testInitiatePaymentTrustedClientIpHeaderPrecedence() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        PaymentInitiateRequest request = new PaymentInitiateRequest(orderId);

        PaymentInitiateResponse response = PaymentInitiateResponse.builder()
                .paymentId(UUID.randomUUID())
                .orderId(orderId)
                .status(PaymentStatus.PENDING)
                .paymentUrl("https://sandbox.vnpayment.vn/pay?token=xyz")
                .build();

        when(paymentService.initiatePayment(eq(request), eq(customerId), eq("118.69.1.2")))
                .thenReturn(response);

        // Send both X-Trusted-Client-IP and a spoofed X-Forwarded-For: only X-Trusted-Client-IP should be used
        mockMvc.perform(post("/api/v1/payments/initiate")
                        .with(user(customerId.toString()).roles("CUSTOMER"))
                        .header("X-Trusted-Client-IP", "118.69.1.2")
                        .header("X-Forwarded-For", "10.0.0.99, 127.0.0.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.data.paymentUrl").value("https://sandbox.vnpayment.vn/pay?token=xyz"));

        verify(paymentService).initiatePayment(eq(request), eq(customerId), eq("118.69.1.2"));
    }

    @Test
    @DisplayName("Positive: GET /api/v1/payments/{paymentId} returns payment details for owner")
    void testGetPaymentByIdReturnsPaymentDetails() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        PaymentResponse response = PaymentResponse.builder()
                .paymentId(paymentId)
                .orderId(orderId)
                .customerId(customerId)
                .amount(new BigDecimal("250000"))
                .currency("VND")
                .provider("VNPAY")
                .status(PaymentStatus.SUCCEEDED)
                .paidAt(Instant.now())
                .build();

        when(paymentService.getPaymentById(eq(paymentId), eq(customerId), eq(false)))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/payments/" + paymentId)
                        .with(user(customerId.toString()).roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.paymentId").value(paymentId.toString()))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
    }

    @Test
    @DisplayName("Negative: GET /api/v1/payments/{paymentId} returns 404 when payment does not exist")
    void testGetPaymentByIdNotFound() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        when(paymentService.getPaymentById(eq(paymentId), eq(customerId), eq(false)))
                .thenThrow(new ResourceNotFoundException("Payment not found with ID: " + paymentId));

        mockMvc.perform(get("/api/v1/payments/" + paymentId)
                        .with(user(customerId.toString()).roles("CUSTOMER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }
}
