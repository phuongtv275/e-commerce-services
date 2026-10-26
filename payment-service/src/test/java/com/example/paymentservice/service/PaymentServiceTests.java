package com.example.paymentservice.service;

import com.ecommerce.common.exception.ConflictException;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.ecommerce.common.exception.ValidationException;
import com.example.paymentservice.config.VnPayProperties;
import com.example.paymentservice.dto.OrderInternalResponse;
import com.example.paymentservice.dto.PaymentInitiateRequest;
import com.example.paymentservice.dto.PaymentInitiateResponse;
import com.example.paymentservice.dto.PaymentResponse;
import com.example.paymentservice.entity.Payment;
import com.example.paymentservice.entity.PaymentAuditLog;
import com.example.paymentservice.entity.PaymentStatus;
import com.example.paymentservice.provider.vnpay.VnPayIpnResponse;
import com.example.paymentservice.provider.vnpay.VnpayClient;
import com.example.paymentservice.repository.PaymentAuditLogRepository;
import com.example.paymentservice.repository.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTests {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentAuditLogRepository auditLogRepository;
    @Mock
    private PaymentOutboxService outboxService;
    @Mock
    private OrderClient orderClient;
    @Mock
    private VnpayClient vnpayClient;
    @Mock
    private VnPayProperties vnPayProperties;
    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private PaymentServiceImpl paymentService;

    private UUID customerId;
    private UUID orderId;
    private OrderInternalResponse pendingOrder;

    @BeforeEach
    void setUp() {
        customerId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        pendingOrder = OrderInternalResponse.builder()
                .orderId(orderId)
                .customerId(customerId)
                .totalAmount(new BigDecimal("100000"))
                .currency("VND")
                .status("PENDING")
                .build();
    }

    @Test
    @DisplayName("Positive: initiatePayment creates signed payment intent successfully")
    void testInitiatePaymentSuccess() {
        when(orderClient.getOrder(orderId)).thenReturn(pendingOrder);
        when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.empty());
        when(vnpayClient.createPaymentUrl(any())).thenReturn("https://sandbox.vnpayment.vn/pay?signed=true");
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment p = invocation.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });

        PaymentInitiateRequest request = new PaymentInitiateRequest(orderId);
        PaymentInitiateResponse response = paymentService.initiatePayment(request, customerId, "118.69.1.2");

        assertNotNull(response);
        assertEquals(orderId, response.getOrderId());
        assertEquals(PaymentStatus.PENDING, response.getStatus());
        assertEquals("https://sandbox.vnpayment.vn/pay?signed=true", response.getPaymentUrl());

        verify(paymentRepository).save(any(Payment.class));
        verify(auditLogRepository).save(any(PaymentAuditLog.class));
    }

    @Test
    @DisplayName("Positive: initiatePayment idempotent retry on PENDING returns existing URL")
    void testInitiatePaymentIdempotentRetry() {
        when(orderClient.getOrder(orderId)).thenReturn(pendingOrder);
        Payment existing = Payment.builder()
                .id(UUID.randomUUID())
                .orderId(orderId)
                .customerId(customerId)
                .amount(new BigDecimal("100000"))
                .status(PaymentStatus.PENDING)
                .paymentUrl("https://sandbox.vnpayment.vn/pay?existing=true")
                .build();
        when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(existing));

        PaymentInitiateRequest request = new PaymentInitiateRequest(orderId);
        PaymentInitiateResponse response = paymentService.initiatePayment(request, customerId, "118.69.1.2");

        assertNotNull(response);
        assertEquals("https://sandbox.vnpayment.vn/pay?existing=true", response.getPaymentUrl());
        verify(vnpayClient, never()).createPaymentUrl(any());
    }

    @Test
    @DisplayName("Negative: initiatePayment rejects terminal FAILED or EXPIRED re-initiation")
    void testInitiatePaymentRejectsTerminalFailed() {
        when(orderClient.getOrder(orderId)).thenReturn(pendingOrder);
        Payment failedPayment = Payment.builder()
                .id(UUID.randomUUID())
                .orderId(orderId)
                .customerId(customerId)
                .status(PaymentStatus.FAILED)
                .build();
        when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(failedPayment));

        PaymentInitiateRequest request = new PaymentInitiateRequest(orderId);
        ConflictException ex = assertThrows(ConflictException.class,
                () -> paymentService.initiatePayment(request, customerId, "1.1.1.1"));
        assertTrue(ex.getMessage().contains("terminal state"));
    }

    @Test
    @DisplayName("Negative: initiatePayment rejects non-owner customer")
    void testInitiatePaymentNonOwnerCustomer() {
        when(orderClient.getOrder(orderId)).thenReturn(pendingOrder);
        UUID anotherCustomer = UUID.randomUUID();

        PaymentInitiateRequest request = new PaymentInitiateRequest(orderId);
        assertThrows(AccessDeniedException.class,
                () -> paymentService.initiatePayment(request, anotherCustomer, "1.1.1.1"));
    }

    @Test
    @DisplayName("Negative: initiatePayment rejects missing client IP")
    void testInitiatePaymentMissingClientIp() {
        PaymentInitiateRequest request = new PaymentInitiateRequest(orderId);
        ValidationException ex = assertThrows(ValidationException.class,
                () -> paymentService.initiatePayment(request, customerId, ""));
        assertTrue(ex.getMessage().contains("Client IP address is required"));
    }

    @Test
    @DisplayName("Negative: initiatePayment rejects order exceeding VNPAY 12-digit cent limit")
    void testInitiatePaymentExceedsMaxAmount() {
        pendingOrder.setTotalAmount(new BigDecimal("10000000000")); // 10 billion VND > 9,999,999,999 limit
        when(orderClient.getOrder(orderId)).thenReturn(pendingOrder);

        PaymentInitiateRequest request = new PaymentInitiateRequest(orderId);
        ValidationException ex = assertThrows(ValidationException.class,
                () -> paymentService.initiatePayment(request, customerId, "1.1.1.1"));
        assertTrue(ex.getMessage().contains("exceeds VNPAY maximum limit"));
    }

    @Test
    @DisplayName("Positive: handleIpnCallback commits SUCCEEDED on 00/00 and emits Outbox event")
    void testHandleIpnSuccess() throws Exception {
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("vnp_TmnCode", "TESTTMN");
        queryParams.put("vnp_TxnRef", "TXN123");
        queryParams.put("vnp_Amount", "10000000"); // 100,000 VND * 100
        queryParams.put("vnp_ResponseCode", "00");
        queryParams.put("vnp_TransactionStatus", "00");
        queryParams.put("vnp_TransactionNo", "14099999");
        queryParams.put("vnp_PayDate", "20261025180000");
        queryParams.put("vnp_IpAddr", "203.0.113.9");
        queryParams.put("vnp_SecureHash", "VALID_HASH");

        when(vnpayClient.verifyIpnSignature(queryParams)).thenReturn(true);
        when(vnPayProperties.getTmnCode()).thenReturn("TESTTMN");
        when(objectMapper.writeValueAsString(anyMap())).thenReturn("{\"vnp_IpAddr\":\"[REDACTED]\"}");

        Payment payment = Payment.builder()
                .id(UUID.randomUUID())
                .orderId(orderId)
                .customerId(customerId)
                .amount(new BigDecimal("100000"))
                .currency("VND")
                .status(PaymentStatus.PENDING)
                .providerTxnRef("TXN123")
                .build();
        when(paymentRepository.findByProviderTxnRefForUpdate("TXN123")).thenReturn(Optional.of(payment));

        VnPayIpnResponse ipnResponse = paymentService.handleIpnCallback(queryParams);

        assertEquals("00", ipnResponse.getRspCode());
        assertEquals("Confirm Success", ipnResponse.getMessage());
        assertEquals(PaymentStatus.SUCCEEDED, payment.getStatus());
        assertEquals("14099999", payment.getProviderTransactionNo());

        verify(paymentRepository).save(payment);
        verify(outboxService).recordPaymentSucceeded(eq(payment.getId()), eq(orderId), eq(new BigDecimal("100000")), eq("VND"), eq("14099999"), any(), isNull());
        org.mockito.ArgumentCaptor<PaymentAuditLog> auditLogCaptor =
                org.mockito.ArgumentCaptor.forClass(PaymentAuditLog.class);
        verify(auditLogRepository).save(auditLogCaptor.capture());
        assertFalse(auditLogCaptor.getValue().getRawPayload().contains("203.0.113.9"));
    }

    @Test
    @DisplayName("Positive: handleIpnCallback is idempotent on SUCCEEDED duplicate and returns 02")
    void testHandleIpnDuplicateSucceeded() {
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("vnp_TmnCode", "TESTTMN");
        queryParams.put("vnp_TxnRef", "TXN123");
        queryParams.put("vnp_Amount", "10000000");
        queryParams.put("vnp_ResponseCode", "00");
        queryParams.put("vnp_TransactionStatus", "00");
        queryParams.put("vnp_SecureHash", "VALID_HASH");

        when(vnpayClient.verifyIpnSignature(queryParams)).thenReturn(true);
        when(vnPayProperties.getTmnCode()).thenReturn("TESTTMN");

        Payment payment = Payment.builder()
                .id(UUID.randomUUID())
                .orderId(orderId)
                .status(PaymentStatus.SUCCEEDED)
                .amount(new BigDecimal("100000"))
                .build();
        when(paymentRepository.findByProviderTxnRefForUpdate("TXN123")).thenReturn(Optional.of(payment));

        VnPayIpnResponse ipnResponse = paymentService.handleIpnCallback(queryParams);

        assertEquals("02", ipnResponse.getRspCode());
        assertEquals("Order already confirmed", ipnResponse.getMessage());
        verify(outboxService, never()).recordPaymentSucceeded(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Positive: handleIpnCallback marks FAILED on failure response code and emits failed event")
    void testHandleIpnFailed() {
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("vnp_TmnCode", "TESTTMN");
        queryParams.put("vnp_TxnRef", "TXN123");
        queryParams.put("vnp_Amount", "10000000");
        queryParams.put("vnp_ResponseCode", "24"); // Customer cancelled
        queryParams.put("vnp_TransactionStatus", "02");
        queryParams.put("vnp_SecureHash", "VALID_HASH");

        when(vnpayClient.verifyIpnSignature(queryParams)).thenReturn(true);
        when(vnPayProperties.getTmnCode()).thenReturn("TESTTMN");

        Payment payment = Payment.builder()
                .id(UUID.randomUUID())
                .orderId(orderId)
                .status(PaymentStatus.PENDING)
                .amount(new BigDecimal("100000"))
                .build();
        when(paymentRepository.findByProviderTxnRefForUpdate("TXN123")).thenReturn(Optional.of(payment));

        VnPayIpnResponse ipnResponse = paymentService.handleIpnCallback(queryParams);

        assertEquals("00", ipnResponse.getRspCode());
        assertEquals(PaymentStatus.FAILED, payment.getStatus());
        verify(outboxService).recordPaymentFailed(eq(payment.getId()), eq(orderId), eq(new BigDecimal("100000")), contains("24"), isNull());
    }

    @Test
    @DisplayName("Positive: handleIpnCallback leaves non-terminal VNPAY status pending")
    void testHandleIpnNonTerminalStatusRemainsPending() {
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("vnp_TmnCode", "TESTTMN");
        queryParams.put("vnp_TxnRef", "TXN123");
        queryParams.put("vnp_Amount", "10000000");
        queryParams.put("vnp_ResponseCode", "99");
        queryParams.put("vnp_TransactionStatus", "01");
        queryParams.put("vnp_SecureHash", "VALID_HASH");

        when(vnpayClient.verifyIpnSignature(queryParams)).thenReturn(true);
        when(vnPayProperties.getTmnCode()).thenReturn("TESTTMN");
        Payment payment = Payment.builder()
                .id(UUID.randomUUID())
                .orderId(orderId)
                .status(PaymentStatus.PENDING)
                .amount(new BigDecimal("100000"))
                .build();
        when(paymentRepository.findByProviderTxnRefForUpdate("TXN123")).thenReturn(Optional.of(payment));

        VnPayIpnResponse response = paymentService.handleIpnCallback(queryParams);

        assertEquals("00", response.getRspCode());
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        verify(outboxService, never()).recordPaymentFailed(any(), any(), any(), any(), any());
        verify(outboxService, never()).recordPaymentSucceeded(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Positive: handleIpnCallback marks late success on EXPIRED payment and emits late-success event")
    void testHandleIpnLateSuccessOnExpired() {
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("vnp_TmnCode", "TESTTMN");
        queryParams.put("vnp_TxnRef", "TXN123");
        queryParams.put("vnp_Amount", "10000000");
        queryParams.put("vnp_ResponseCode", "00");
        queryParams.put("vnp_TransactionStatus", "00");
        queryParams.put("vnp_TransactionNo", "14099999");
        queryParams.put("vnp_SecureHash", "VALID_HASH");

        when(vnpayClient.verifyIpnSignature(queryParams)).thenReturn(true);
        when(vnPayProperties.getTmnCode()).thenReturn("TESTTMN");

        Payment payment = Payment.builder()
                .id(UUID.randomUUID())
                .orderId(orderId)
                .status(PaymentStatus.EXPIRED)
                .amount(new BigDecimal("100000"))
                .currency("VND")
                .build();
        when(paymentRepository.findByProviderTxnRefForUpdate("TXN123")).thenReturn(Optional.of(payment));

        VnPayIpnResponse ipnResponse = paymentService.handleIpnCallback(queryParams);

        assertEquals("00", ipnResponse.getRspCode());
        assertEquals(PaymentStatus.SUCCEEDED, payment.getStatus());
        verify(outboxService).recordPaymentLateSuccess(eq(payment.getId()), eq(orderId), eq(new BigDecimal("100000")), eq("VND"), eq("14099999"), contains("EXPIRED"), isNull());
    }

    @Test
    @DisplayName("Negative: handleIpnCallback returns 97 on invalid checksum")
    void testHandleIpnInvalidChecksum() {
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("vnp_SecureHash", "INVALID");

        when(vnpayClient.verifyIpnSignature(queryParams)).thenReturn(false);

        VnPayIpnResponse ipnResponse = paymentService.handleIpnCallback(queryParams);

        assertEquals("97", ipnResponse.getRspCode());
        assertEquals("Invalid Checksum", ipnResponse.getMessage());
        verify(paymentRepository, never()).findByProviderTxnRefForUpdate(any());
    }

    @Test
    @DisplayName("Negative: handleIpnCallback returns 04 on amount mismatch")
    void testHandleIpnAmountMismatch() {
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("vnp_TmnCode", "TESTTMN");
        queryParams.put("vnp_TxnRef", "TXN123");
        queryParams.put("vnp_Amount", "5000000"); // 50,000 VND instead of 100,000 VND
        queryParams.put("vnp_SecureHash", "VALID_HASH");

        when(vnpayClient.verifyIpnSignature(queryParams)).thenReturn(true);
        when(vnPayProperties.getTmnCode()).thenReturn("TESTTMN");

        Payment payment = Payment.builder()
                .id(UUID.randomUUID())
                .orderId(orderId)
                .status(PaymentStatus.PENDING)
                .amount(new BigDecimal("100000"))
                .build();
        when(paymentRepository.findByProviderTxnRefForUpdate("TXN123")).thenReturn(Optional.of(payment));

        VnPayIpnResponse ipnResponse = paymentService.handleIpnCallback(queryParams);

        assertEquals("04", ipnResponse.getRspCode());
        assertEquals("Invalid amount", ipnResponse.getMessage());
        assertEquals(PaymentStatus.PENDING, payment.getStatus()); // Untouched
    }
}
