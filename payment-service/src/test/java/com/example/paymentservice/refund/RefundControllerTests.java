package com.example.paymentservice.refund;

import com.ecommerce.common.exception.ConflictException;
import com.ecommerce.common.exception.GlobalExceptionHandler;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.example.paymentservice.entity.RefundStatus;
import com.example.paymentservice.refund.controller.RefundController;
import com.example.paymentservice.refund.dto.RefundRequest;
import com.example.paymentservice.refund.dto.RefundResolution;
import com.example.paymentservice.refund.dto.RefundResponse;
import com.example.paymentservice.refund.dto.ResolveRefundRequest;
import com.example.paymentservice.refund.service.RefundService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class RefundControllerTests {

    @Mock
    private RefundService refundService;

    @InjectMocks
    private RefundController refundController;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(refundController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("Positive: POST /api/v1/admin/payments/{paymentId}/refunds returns 200 with refund data")
    void createRefund_success() throws Exception {
        UUID paymentId = UUID.randomUUID();
        RefundRequest request = RefundRequest.builder()
                .amount(new BigDecimal("50000"))
                .reason("Customer requested refund")
                .idempotencyKey("KEY_123")
                .build();

        RefundResponse response = RefundResponse.builder()
                .id(UUID.randomUUID())
                .paymentId(paymentId)
                .amount(new BigDecimal("50000"))
                .status(RefundStatus.PENDING)
                .idempotencyKey("KEY_123")
                .build();

        when(refundService.createManualRefund(eq(paymentId), any(RefundRequest.class), any()))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/admin/payments/{paymentId}/refunds", paymentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(response.getId().toString()))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    @DisplayName("Negative: POST refund with missing amount returns 400 Bad Request")
    void createRefund_missingAmount_returns400() throws Exception {
        UUID paymentId = UUID.randomUUID();
        RefundRequest request = RefundRequest.builder()
                .amount(null)
                .reason("Reason")
                .idempotencyKey("KEY")
                .build();

        mockMvc.perform(post("/api/v1/admin/payments/{paymentId}/refunds", paymentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Negative: POST refund with negative amount returns 400 Bad Request")
    void createRefund_negativeAmount_returns400() throws Exception {
        UUID paymentId = UUID.randomUUID();
        RefundRequest request = RefundRequest.builder()
                .amount(new BigDecimal("-1000"))
                .reason("Reason")
                .idempotencyKey("KEY")
                .build();

        mockMvc.perform(post("/api/v1/admin/payments/{paymentId}/refunds", paymentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Positive: POST /api/v1/admin/payments/{paymentId}/refunds/{refundId}/resolve returns 200")
    void resolveRefund_success() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();

        ResolveRefundRequest request = ResolveRefundRequest.builder()
                .resolution(RefundResolution.CONFIRM_SUCCEEDED)
                .providerRefundId("PROV_123")
                .note("Manual confirmation")
                .build();

        RefundResponse response = RefundResponse.builder()
                .id(refundId)
                .paymentId(paymentId)
                .status(RefundStatus.SUCCEEDED)
                .providerTransactionNo("PROV_123")
                .build();

        when(refundService.resolveRefund(eq(paymentId), eq(refundId), any(ResolveRefundRequest.class), any()))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/admin/payments/{paymentId}/refunds/{refundId}/resolve", paymentId, refundId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
    }

    @Test
    @DisplayName("Negative: POST resolve returns 409 Conflict if refund not in manual reconciliation")
    void resolveRefund_conflict_returns409() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();

        ResolveRefundRequest request = ResolveRefundRequest.builder()
                .resolution(RefundResolution.CONFIRM_SUCCEEDED)
                .build();

        when(refundService.resolveRefund(eq(paymentId), eq(refundId), any(ResolveRefundRequest.class), any()))
                .thenThrow(new ConflictException("Refund status does not permit resolution"));

        mockMvc.perform(post("/api/v1/admin/payments/{paymentId}/refunds/{refundId}/resolve", paymentId, refundId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Negative: POST resolve with providerRefundId longer than 15 characters returns 400 Bad Request")
    void resolveRefund_providerRefundIdExceeds15_returns400() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();

        ResolveRefundRequest request = ResolveRefundRequest.builder()
                .resolution(RefundResolution.CONFIRM_SUCCEEDED)
                .providerRefundId("1234567890123456") // 16 characters > 15 max
                .note("Test note")
                .build();

        mockMvc.perform(post("/api/v1/admin/payments/{paymentId}/refunds/{refundId}/resolve", paymentId, refundId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Positive: POST resolve with providerRefundId of exactly 15 characters returns 200 OK")
    void resolveRefund_providerRefundIdMax15_success() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();

        ResolveRefundRequest request = ResolveRefundRequest.builder()
                .resolution(RefundResolution.CONFIRM_SUCCEEDED)
                .providerRefundId("123456789012345") // exactly 15 characters
                .note("Max length refund id")
                .build();

        RefundResponse response = RefundResponse.builder()
                .id(refundId)
                .paymentId(paymentId)
                .status(RefundStatus.SUCCEEDED)
                .providerTransactionNo("123456789012345")
                .build();

        when(refundService.resolveRefund(eq(paymentId), eq(refundId), any(ResolveRefundRequest.class), any()))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/admin/payments/{paymentId}/refunds/{refundId}/resolve", paymentId, refundId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }
}
