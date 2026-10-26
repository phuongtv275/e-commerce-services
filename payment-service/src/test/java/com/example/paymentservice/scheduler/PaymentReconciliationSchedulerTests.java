package com.example.paymentservice.scheduler;

import com.ecommerce.common.outbox.OutboxEvent;
import com.ecommerce.common.outbox.OutboxEventRepository;
import com.example.paymentservice.entity.Payment;
import com.example.paymentservice.entity.PaymentStatus;
import com.example.paymentservice.provider.vnpay.VnPayQueryRequest;
import com.example.paymentservice.provider.vnpay.VnPayQueryResponse;
import com.example.paymentservice.provider.vnpay.VnpayClient;
import com.example.paymentservice.repository.PaymentAuditLogRepository;
import com.example.paymentservice.repository.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentReconciliationSchedulerTests {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentAuditLogRepository auditLogRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private VnpayClient vnpayClient;

    @Mock
    private PlatformTransactionManager transactionManager;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private PaymentReconciliationScheduler scheduler;

    @BeforeEach
    void setUp() {
        TransactionStatus status = mock(TransactionStatus.class);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(status);

        com.example.paymentservice.config.VnPayProperties vnPayProperties = new com.example.paymentservice.config.VnPayProperties();
        vnPayProperties.setIpAddress("198.51.100.1");
        vnPayProperties.setTmnCode("TESTTMN");

        scheduler = new PaymentReconciliationScheduler(
                paymentRepository,
                auditLogRepository,
                outboxEventRepository,
                vnpayClient,
                vnPayProperties,
                objectMapper,
                transactionManager
        );
        ReflectionTestUtils.setField(scheduler, "pendingThresholdMinutes", 30);
    }

    @Test
    @DisplayName("Positive: Reconciles PENDING payment to SUCCEEDED when VNPAY query returns 00/00")
    void reconcilePendingPayments_success() {
        UUID paymentId = UUID.randomUUID();
        Payment payment = Payment.builder()
                .id(paymentId)
                .orderId(UUID.randomUUID())
                .customerId(UUID.randomUUID())
                .amount(new BigDecimal("100000"))
                .currency("VND")
                .providerTxnRef("TXN_1")
                .providerCreateDate("20261025100000")
                .status(PaymentStatus.PENDING)
                .build();

        when(paymentRepository.claimPaymentsForReconciliation(eq(PaymentStatus.PENDING.name()), any(Instant.class), any(Instant.class), anyInt()))
                .thenReturn(List.of(payment));
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(payment));

        VnPayQueryResponse queryResponse = VnPayQueryResponse.builder()
                .responseCode("00")
                .tmnCode("TESTTMN")
                .txnRef("TXN_1")
                .amount(new BigDecimal("10000000"))
                .transactionStatus("00")
                .transactionNo("QUERY_TXN_888")
                .build();
        when(vnpayClient.queryTransaction(any(VnPayQueryRequest.class))).thenReturn(queryResponse);

        scheduler.reconcilePendingPayments();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getReconciliationClaimUntil()).isNotNull();
        assertThat(payment.getProviderTransactionNo()).isEqualTo("QUERY_TXN_888");
        verify(outboxEventRepository, times(1)).save(any(OutboxEvent.class));
        verify(paymentRepository, times(1)).save(payment);
        org.mockito.ArgumentCaptor<OutboxEvent> outboxCaptor = org.mockito.ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getAggregateId()).isEqualTo(payment.getOrderId().toString());
    }

    @Test
    @DisplayName("Positive: Reconciles PENDING payment to FAILED when VNPAY query returns 00/02")
    void reconcilePendingPayments_failed() {
        UUID paymentId = UUID.randomUUID();
        Payment payment = Payment.builder()
                .id(paymentId)
                .orderId(UUID.randomUUID())
                .customerId(UUID.randomUUID())
                .amount(new BigDecimal("100000"))
                .currency("VND")
                .providerTxnRef("TXN_1")
                .providerCreateDate("20261025100000")
                .status(PaymentStatus.PENDING)
                .build();

        when(paymentRepository.claimPaymentsForReconciliation(eq(PaymentStatus.PENDING.name()), any(Instant.class), any(Instant.class), anyInt()))
                .thenReturn(List.of(payment));
        when(paymentRepository.findByIdForUpdate(paymentId)).thenReturn(Optional.of(payment));

        VnPayQueryResponse queryResponse = VnPayQueryResponse.builder()
                .responseCode("00")
                .tmnCode("TESTTMN")
                .txnRef("TXN_1")
                .amount(new BigDecimal("10000000"))
                .transactionStatus("02")
                .build();
        when(vnpayClient.queryTransaction(any(VnPayQueryRequest.class))).thenReturn(queryResponse);

        scheduler.reconcilePendingPayments();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(outboxEventRepository, times(1)).save(any(OutboxEvent.class));
        verify(paymentRepository, times(1)).save(payment);
    }

    @Test
    @DisplayName("Negative: ignores querydr success response with mismatched amount")
    void reconcilePendingPayments_ignoresMismatchedResponse() {
        UUID paymentId = UUID.randomUUID();
        Payment payment = Payment.builder()
                .id(paymentId)
                .orderId(UUID.randomUUID())
                .customerId(UUID.randomUUID())
                .amount(new BigDecimal("100000"))
                .currency("VND")
                .providerTxnRef("TXN_1")
                .providerCreateDate("20261025100000")
                .status(PaymentStatus.PENDING)
                .build();
        when(paymentRepository.claimPaymentsForReconciliation(eq(PaymentStatus.PENDING.name()), any(Instant.class), any(Instant.class), anyInt()))
                .thenReturn(List.of(payment));
        when(vnpayClient.queryTransaction(any(VnPayQueryRequest.class))).thenReturn(VnPayQueryResponse.builder()
                .responseCode("00")
                .tmnCode("TESTTMN")
                .txnRef("TXN_1")
                .amount(new BigDecimal("9999999"))
                .transactionStatus("00")
                .build());

        scheduler.reconcilePendingPayments();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(outboxEventRepository, never()).save(any());
    }
}
