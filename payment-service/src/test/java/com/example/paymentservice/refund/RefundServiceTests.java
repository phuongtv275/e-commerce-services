package com.example.paymentservice.refund;

import com.ecommerce.common.event.RefundCompletedEvent;
import com.ecommerce.common.exception.ConflictException;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.ecommerce.common.outbox.OutboxEvent;
import com.ecommerce.common.outbox.OutboxEventRepository;
import com.example.paymentservice.entity.*;
import com.example.paymentservice.provider.vnpay.*;
import com.example.paymentservice.refund.dto.RefundRequest;
import com.example.paymentservice.refund.dto.RefundResolution;
import com.example.paymentservice.refund.dto.RefundResponse;
import com.example.paymentservice.refund.dto.ResolveRefundRequest;
import com.example.paymentservice.refund.service.RefundServiceImpl;
import com.example.paymentservice.repository.PaymentAuditLogRepository;
import com.example.paymentservice.repository.PaymentRepository;
import com.example.paymentservice.repository.RefundRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefundServiceTests {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private RefundRepository refundRepository;

    @Mock
    private PaymentAuditLogRepository auditLogRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private VnpayClient vnpayClient;

    @Mock
    private PlatformTransactionManager transactionManager;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private RefundServiceImpl refundService;

    @BeforeEach
    void setUp() {
        TransactionStatus status = mock(TransactionStatus.class);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(status);

        com.example.paymentservice.config.VnPayProperties vnPayProperties = new com.example.paymentservice.config.VnPayProperties();
        vnPayProperties.setIpAddress("198.51.100.1");

        refundService = new RefundServiceImpl(
                paymentRepository,
                refundRepository,
                auditLogRepository,
                outboxEventRepository,
                vnpayClient,
                vnPayProperties,
                objectMapper,
                transactionManager
        );
    }

    @Test
    @DisplayName("Positive: Manual refund succeeds, updates hold and balance, emits outbox event")
    void manualRefund_success() {
        UUID paymentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        Instant paidAtTime = Instant.parse("2026-10-25T05:30:00Z"); // 12:30:00 GMT+7 -> 20261025123000
        Payment payment = Payment.builder()
                .id(paymentId)
                .orderId(orderId)
                .amount(new BigDecimal("100000"))
                .currency("VND")
                .provider("VNPAY")
                .status(PaymentStatus.SUCCEEDED)
                .providerTxnRef("TXN_12345")
                .providerCreateDate("20261025110000") // intent create date (11:00)
                .paidAt(paidAtTime)                   // actual paidAt date (12:30)
                .providerTransactionNo("145678")
                .totalRefunded(BigDecimal.ZERO)
                .refundInProgress(BigDecimal.ZERO)
                .build();

        RefundRequest request = RefundRequest.builder()
                .amount(new BigDecimal("50000"))
                .reason("Customer request")
                .idempotencyKey("KEY_123")
                .build();

        when(refundRepository.findByIdempotencyKey("KEY_123")).thenReturn(Optional.empty());
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(payment));
        when(refundRepository.findByPaymentId(paymentId)).thenReturn(Collections.emptyList());

        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> {
            Refund r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });

        VnPayRefundResponse vnpayResponse = VnPayRefundResponse.builder()
                .responseCode("00")
                .transactionStatus("00")
                .transactionNo("REFUND_TXN_999")
                .build();
        ArgumentCaptor<VnPayRefundRequest> requestCaptor = ArgumentCaptor.forClass(VnPayRefundRequest.class);
        when(vnpayClient.refund(requestCaptor.capture())).thenReturn(vnpayResponse);

        RefundResponse response = refundService.createManualRefund(paymentId, request, actorId);

        assertThat(response).isNotNull();
        assertThat(payment.getTotalRefunded()).isEqualByComparingTo("50000");
        assertThat(payment.getRefundInProgress()).isEqualByComparingTo("0");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);

        // Verify transactionDate is the original payment vnp_CreateDate (providerCreateDate) per VNPAY docs
        VnPayRefundRequest capturedReq = requestCaptor.getValue();
        assertThat(capturedReq.getTransactionDate()).isEqualTo(payment.getProviderCreateDate());
        assertThat(capturedReq.getTransactionDate()).isEqualTo("20261025110000");
        assertThat(capturedReq.getIpAddress()).isNotEqualTo("127.0.0.1");

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getAggregateId()).isEqualTo(orderId.toString());
        verify(auditLogRepository, atLeastOnce()).save(any(PaymentAuditLog.class));
    }

    @Test
    @DisplayName("Positive: Idempotent replay returns existing refund without calling VNPAY twice")
    void manualRefund_idempotentReplay() {
        UUID paymentId = UUID.randomUUID();
        Refund existingRefund = Refund.builder()
                .id(UUID.randomUUID())
                .paymentId(paymentId)
                .orderId(UUID.randomUUID())
                .amount(new BigDecimal("50000"))
                .reason("Prior request")
                .status(RefundStatus.SUCCEEDED)
                .idempotencyKey("EXISTING_KEY")
                .providerRequestId("REQ_999")
                .build();

        when(refundRepository.findByIdempotencyKey("EXISTING_KEY")).thenReturn(Optional.of(existingRefund));

        RefundRequest request = RefundRequest.builder()
                .amount(new BigDecimal("50000"))
                .reason("Prior request")
                .idempotencyKey("EXISTING_KEY")
                .build();

        RefundResponse response = refundService.createManualRefund(paymentId, request, UUID.randomUUID());

        assertThat(response.getId()).isEqualTo(existingRefund.getId());
        verify(vnpayClient, never()).refund(any());
    }

    @Test
    @DisplayName("Positive: concurrent idempotency key conflict returns the committed refund")
    void manualRefund_uniqueKeyRaceReturnsExisting() {
        UUID paymentId = UUID.randomUUID();
        Refund existingRefund = Refund.builder()
                .id(UUID.randomUUID())
                .paymentId(paymentId)
                .orderId(UUID.randomUUID())
                .amount(new BigDecimal("50000"))
                .reason("Prior request")
                .status(RefundStatus.PENDING)
                .idempotencyKey("RACE_KEY")
                .providerRequestId("REQ_RACE")
                .build();
        Payment payment = Payment.builder()
                .id(paymentId)
                .orderId(existingRefund.getOrderId())
                .status(PaymentStatus.SUCCEEDED)
                .amount(new BigDecimal("100000"))
                .totalRefunded(BigDecimal.ZERO)
                .refundInProgress(BigDecimal.ZERO)
                .build();
        when(refundRepository.findByIdempotencyKey("RACE_KEY"))
                .thenReturn(Optional.empty(), Optional.empty(), Optional.of(existingRefund));
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(payment));
        when(refundRepository.findByPaymentId(paymentId)).thenReturn(Collections.emptyList());
        when(refundRepository.save(any(Refund.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate idempotency key"));

        RefundRequest request = RefundRequest.builder()
                .amount(new BigDecimal("50000"))
                .reason("Customer request")
                .idempotencyKey("RACE_KEY")
                .build();

        RefundResponse response = refundService.createManualRefund(paymentId, request, UUID.randomUUID());

        assertThat(response.getId()).isEqualTo(existingRefund.getId());
        verify(vnpayClient, never()).refund(any());
    }

    @Test
    @DisplayName("Negative: Refund amount exceeding ceiling throws ConflictException")
    void manualRefund_exceedsCeiling_throwsConflict() {
        UUID paymentId = UUID.randomUUID();
        Payment payment = Payment.builder()
                .id(paymentId)
                .orderId(UUID.randomUUID())
                .amount(new BigDecimal("100000"))
                .status(PaymentStatus.SUCCEEDED)
                .totalRefunded(new BigDecimal("60000"))
                .refundInProgress(new BigDecimal("20000"))
                .build();

        when(refundRepository.findByIdempotencyKey("KEY_OVER")).thenReturn(Optional.empty());
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(payment));

        RefundRequest request = RefundRequest.builder()
                .amount(new BigDecimal("30000")) // available is 20000
                .reason("Too much")
                .idempotencyKey("KEY_OVER")
                .build();

        assertThatThrownBy(() -> refundService.createManualRefund(paymentId, request, UUID.randomUUID()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("exceeds available ceiling");
    }

    @Test
    @DisplayName("Negative: VNPAY timeout retains hold in PENDING status")
    void manualRefund_vnpayTimeout_retainsHold() {
        UUID paymentId = UUID.randomUUID();
        Payment payment = Payment.builder()
                .id(paymentId)
                .orderId(UUID.randomUUID())
                .amount(new BigDecimal("100000"))
                .status(PaymentStatus.SUCCEEDED)
                .providerTxnRef("TXN_1")
                .providerCreateDate("20261025120000")
                .totalRefunded(BigDecimal.ZERO)
                .refundInProgress(BigDecimal.ZERO)
                .build();

        RefundRequest request = RefundRequest.builder()
                .amount(new BigDecimal("50000"))
                .reason("Test timeout")
                .idempotencyKey("KEY_TIMEOUT")
                .build();

        when(refundRepository.findByIdempotencyKey("KEY_TIMEOUT")).thenReturn(Optional.empty());
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(payment));

        Refund savedRefund = Refund.builder()
                .id(UUID.randomUUID())
                .paymentId(paymentId)
                .orderId(payment.getOrderId())
                .amount(new BigDecimal("50000"))
                .status(RefundStatus.PENDING)
                .idempotencyKey("KEY_TIMEOUT")
                .providerRequestId("REQ_TIME")
                .build();

        when(refundRepository.save(any(Refund.class))).thenReturn(savedRefund);
        when(refundRepository.findById(any())).thenReturn(Optional.of(savedRefund));

        // Simulate VNPAY timeout
        when(vnpayClient.refund(any())).thenThrow(new RuntimeException("Connection timed out"));

        RefundResponse response = refundService.createManualRefund(paymentId, request, UUID.randomUUID());

        assertThat(response.getStatus()).isEqualTo(RefundStatus.PENDING);
        // Hold is retained
        assertThat(payment.getRefundInProgress()).isEqualByComparingTo("50000");
        assertThat(payment.getTotalRefunded()).isEqualByComparingTo("0");
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("Positive: Manual resolution CONFIRM_SUCCEEDED updates balance and records outbox")
    void resolveRefund_confirmSucceeded() {
        UUID paymentId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();

        Refund refund = Refund.builder()
                .id(refundId)
                .paymentId(paymentId)
                .orderId(UUID.randomUUID())
                .amount(new BigDecimal("50000"))
                .status(RefundStatus.MANUAL_RECONCILIATION_REQUIRED)
                .build();

        Payment payment = Payment.builder()
                .id(paymentId)
                .orderId(refund.getOrderId())
                .amount(new BigDecimal("50000"))
                .currency("VND")
                .totalRefunded(BigDecimal.ZERO)
                .refundInProgress(new BigDecimal("50000"))
                .status(PaymentStatus.MANUAL_RECONCILIATION_REQUIRED)
                .build();

        when(refundRepository.findByIdForUpdate(refundId)).thenReturn(Optional.of(refund));
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(payment));
        when(refundRepository.findByPaymentId(paymentId)).thenReturn(List.of(refund));

        ResolveRefundRequest request = ResolveRefundRequest.builder()
                .resolution(RefundResolution.CONFIRM_SUCCEEDED)
                .providerRefundId("RESOLVED_999")
                .note("Verified with VNPAY portal")
                .build();

        RefundResponse response = refundService.resolveRefund(paymentId, refundId, request, "ADMIN_USER");

        assertThat(response.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(payment.getTotalRefunded()).isEqualByComparingTo("50000");
        assertThat(payment.getRefundInProgress()).isEqualByComparingTo("0");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);

        verify(outboxEventRepository, times(1)).save(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("Positive: Manual resolution CONFIRM_FAILED releases hold")
    void resolveRefund_confirmFailed() {
        UUID paymentId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();

        Refund refund = Refund.builder()
                .id(refundId)
                .paymentId(paymentId)
                .orderId(UUID.randomUUID())
                .amount(new BigDecimal("50000"))
                .status(RefundStatus.MANUAL_RECONCILIATION_REQUIRED)
                .build();

        Payment payment = Payment.builder()
                .id(paymentId)
                .orderId(refund.getOrderId())
                .amount(new BigDecimal("100000"))
                .currency("VND")
                .totalRefunded(BigDecimal.ZERO)
                .refundInProgress(new BigDecimal("50000"))
                .status(PaymentStatus.MANUAL_RECONCILIATION_REQUIRED)
                .build();

        when(refundRepository.findByIdForUpdate(refundId)).thenReturn(Optional.of(refund));
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(payment));
        when(refundRepository.findByPaymentId(paymentId)).thenReturn(List.of(refund));

        ResolveRefundRequest request = ResolveRefundRequest.builder()
                .resolution(RefundResolution.CONFIRM_FAILED)
                .note("Bank rejected refund")
                .build();

        RefundResponse response = refundService.resolveRefund(paymentId, refundId, request, "ADMIN_USER");

        assertThat(response.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(payment.getRefundInProgress()).isEqualByComparingTo("0");
        assertThat(payment.getTotalRefunded()).isEqualByComparingTo("0");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("Positive: processAutoRefund claims full refundable amount and invokes VNPAY")
    void processAutoRefund_success() {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        Payment payment = Payment.builder()
                .id(paymentId)
                .orderId(orderId)
                .amount(new BigDecimal("200000"))
                .currency("VND")
                .provider("VNPAY")
                .status(PaymentStatus.SUCCEEDED)
                .providerTxnRef("TXN_AUTO")
                .providerCreateDate("20261025120000")
                .providerTransactionNo("998877")
                .totalRefunded(BigDecimal.ZERO)
                .refundInProgress(BigDecimal.ZERO)
                .build();

        when(refundRepository.findByIdempotencyKey("AUTO_REFUND:" + orderId)).thenReturn(Optional.empty());
        when(paymentRepository.findByOrderIdForUpdate(orderId)).thenReturn(Optional.of(payment));
        when(refundRepository.findByPaymentId(paymentId)).thenReturn(Collections.emptyList());

        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> {
            Refund r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });
        when(refundRepository.findById(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return Optional.of(Refund.builder()
                    .id(id)
                    .paymentId(paymentId)
                    .orderId(orderId)
                    .amount(new BigDecimal("200000"))
                    .status(RefundStatus.SUCCEEDED)
                    .build());
        });

        VnPayRefundResponse vnpayResponse = VnPayRefundResponse.builder()
                .responseCode("00")
                .transactionStatus("00")
                .transactionNo("REFUND_AUTO_1")
                .build();
        when(vnpayClient.refund(any(VnPayRefundRequest.class))).thenReturn(vnpayResponse);

        Refund refund = refundService.processAutoRefund(orderId, null, "Compensation", "CORR_A");

        assertThat(refund).isNotNull();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(payment.getTotalRefunded()).isEqualByComparingTo("200000");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    @DisplayName("Positive: reconcilePendingRefund moves to MANUAL_RECONCILIATION_REQUIRED after 3 retries")
    void reconcilePendingRefund_movesToManualAfter3Retries() {
        UUID refundId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        Refund refund = Refund.builder()
                .id(refundId)
                .paymentId(paymentId)
                .orderId(UUID.randomUUID())
                .status(RefundStatus.PENDING)
                .retryCount(3)
                .build();

        Payment payment = Payment.builder()
                .id(paymentId)
                .status(PaymentStatus.REFUND_PENDING)
                .build();

        when(refundRepository.findByIdForUpdate(refundId)).thenReturn(Optional.of(refund));
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(payment));

        refundService.reconcilePendingRefund(refundId);

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.MANUAL_RECONCILIATION_REQUIRED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.MANUAL_RECONCILIATION_REQUIRED);
        verify(vnpayClient, never()).queryTransaction(any());
    }

    @Test
    @DisplayName("Negative/Ambiguous: VNPAY responseCode=00 with null transactionStatus retains hold in PENDING")
    void manualRefund_responseCode00_nullTxStatus_retainsHold() {
        UUID paymentId = UUID.randomUUID();
        Payment payment = Payment.builder()
                .id(paymentId)
                .orderId(UUID.randomUUID())
                .amount(new BigDecimal("100000"))
                .status(PaymentStatus.SUCCEEDED)
                .providerTxnRef("TXN_NULL_STATUS")
                .providerCreateDate("20261025120000")
                .totalRefunded(BigDecimal.ZERO)
                .refundInProgress(BigDecimal.ZERO)
                .build();

        RefundRequest request = RefundRequest.builder()
                .amount(new BigDecimal("50000"))
                .reason("Test accepted not final")
                .idempotencyKey("KEY_ACCEPTED")
                .build();

        when(refundRepository.findByIdempotencyKey("KEY_ACCEPTED")).thenReturn(Optional.empty());
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(payment));

        Refund savedRefund = Refund.builder()
                .id(UUID.randomUUID())
                .paymentId(paymentId)
                .orderId(payment.getOrderId())
                .amount(new BigDecimal("50000"))
                .status(RefundStatus.PENDING)
                .idempotencyKey("KEY_ACCEPTED")
                .providerRequestId("REQ_ACCEPTED")
                .build();

        when(refundRepository.save(any(Refund.class))).thenReturn(savedRefund);
        when(refundRepository.findById(any())).thenReturn(Optional.of(savedRefund));

        // Provider accepted request (00) but transactionStatus is null (not final yet)
        VnPayRefundResponse vnpayResponse = VnPayRefundResponse.builder()
                .responseCode("00")
                .transactionStatus(null)
                .message("Request received")
                .build();
        when(vnpayClient.refund(any())).thenReturn(vnpayResponse);

        RefundResponse response = refundService.createManualRefund(paymentId, request, UUID.randomUUID());

        // Status must remain PENDING and hold must be retained
        assertThat(response.getStatus()).isEqualTo(RefundStatus.PENDING);
        assertThat(payment.getRefundInProgress()).isEqualByComparingTo("50000");
        assertThat(payment.getTotalRefunded()).isEqualByComparingTo("0");
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("Ambiguous: querydr payment status=00 does not mark refund as SUCCEEDED, retains hold and moves to manual on retry 3")
    void reconcilePendingRefund_querydrDoesNotMarkSuccess_retainsHold() {
        UUID refundId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        Refund refund = Refund.builder()
                .id(refundId)
                .paymentId(paymentId)
                .orderId(UUID.randomUUID())
                .status(RefundStatus.PENDING)
                .retryCount(2) // 2 retries so next attempt reaches 3
                .build();

        Payment payment = Payment.builder()
                .id(paymentId)
                .orderId(refund.getOrderId())
                .amount(new BigDecimal("100000"))
                .status(PaymentStatus.REFUND_PENDING)
                .providerTxnRef("TXN_QUERYDR")
                .providerCreateDate("20261025100000")
                .paidAt(Instant.parse("2026-10-25T03:30:00Z")) // 20261025103000
                .build();

        when(refundRepository.findByIdForUpdate(refundId)).thenReturn(Optional.of(refund));
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(payment));

        // querydr returns payment status 00 (original payment succeeded, not refund!)
        VnPayQueryResponse queryResponse = VnPayQueryResponse.builder()
                .responseCode("00")
                .transactionStatus("00")
                .build();
        ArgumentCaptor<VnPayQueryRequest> queryCaptor = ArgumentCaptor.forClass(VnPayQueryRequest.class);
        when(vnpayClient.queryTransaction(queryCaptor.capture())).thenReturn(queryResponse);

        refundService.reconcilePendingRefund(refundId);

        // Assert querydr was queried with original payment vnp_CreateDate (providerCreateDate) per VNPAY docs
        assertThat(queryCaptor.getValue().getTransactionDate()).isEqualTo(payment.getProviderCreateDate());
        assertThat(queryCaptor.getValue().getTransactionDate()).isEqualTo("20261025100000");
        assertThat(queryCaptor.getValue().getIpAddress()).isNotEqualTo("127.0.0.1");

        // Refund must NOT be marked SUCCEEDED; because retryCount reached 3, it moves to MANUAL_RECONCILIATION_REQUIRED
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.MANUAL_RECONCILIATION_REQUIRED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.MANUAL_RECONCILIATION_REQUIRED);
        verify(outboxEventRepository, never()).save(any());
    }
}
