package com.example.paymentservice.consumer;

import com.ecommerce.common.event.OrderCancelledEvent;
import com.ecommerce.common.event.PaymentLateSuccessEvent;
import com.ecommerce.common.event.ReservationCommitFailedEvent;
import com.ecommerce.common.event.ReservationExpiredEvent;
import com.ecommerce.common.idempotency.IdempotentConsumerHelper;
import com.example.paymentservice.entity.Payment;
import com.example.paymentservice.entity.PaymentAuditLog;
import com.example.paymentservice.entity.PaymentStatus;
import com.example.paymentservice.refund.service.RefundService;
import com.example.paymentservice.repository.PaymentAuditLogRepository;
import com.example.paymentservice.repository.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class PaymentSagaConsumerTests {

    @Mock
    private RefundService refundService;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentAuditLogRepository auditLogRepository;

    @Mock
    private IdempotentConsumerHelper idempotentConsumerHelper;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private Acknowledgment acknowledgment;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private PaymentSagaConsumer consumer;

    @BeforeEach
    void setUp() {
        TransactionStatus status = mock(TransactionStatus.class);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(status);

        // Make idempotentConsumerHelper execute the consumer logic directly in tests
        lenient().when(idempotentConsumerHelper.processIdempotent(any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    Consumer<Object> logic = inv.getArgument(3);
                    logic.accept(inv.getArgument(2));
                    return true;
                });

        consumer = new PaymentSagaConsumer(
                refundService,
                paymentRepository,
                auditLogRepository,
                idempotentConsumerHelper,
                objectMapper,
                transactionManager
        );
    }

    @Test
    @DisplayName("Positive: order.cancelled with refundRequired=true triggers auto refund and acknowledges")
    void consumeOrderCancelled_refundRequiredTrue() throws Exception {
        UUID orderId = UUID.randomUUID();
        OrderCancelledEvent event = OrderCancelledEvent.builder()
                .eventId(UUID.randomUUID())
                .orderId(orderId)
                .refundRequired(true)
                .reason("User cancelled")
                .correlationId("CORR_1")
                .build();

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "order.cancelled", 0, 0L, orderId.toString(), objectMapper.writeValueAsString(event)
        );

        consumer.consumeOrderCancelled(record, acknowledgment);

        verify(refundService, times(1)).processAutoRefund(eq(orderId), isNull(), eq("User cancelled"), eq("CORR_1"));
        verify(acknowledgment, times(1)).acknowledge();
    }

    @Test
    @DisplayName("Positive: order.cancelled with refundRequired=false is no-op and acknowledges")
    void consumeOrderCancelled_refundRequiredFalse() throws Exception {
        UUID orderId = UUID.randomUUID();
        OrderCancelledEvent event = OrderCancelledEvent.builder()
                .eventId(UUID.randomUUID())
                .orderId(orderId)
                .refundRequired(false)
                .reason("Order cancelled before payment")
                .build();

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "order.cancelled", 0, 0L, orderId.toString(), objectMapper.writeValueAsString(event)
        );

        consumer.consumeOrderCancelled(record, acknowledgment);

        verify(refundService, never()).processAutoRefund(any(), any(), any(), any());
        verify(acknowledgment, times(1)).acknowledge();
    }

    @Test
    @DisplayName("Negative: malformed order.cancelled is not acknowledged")
    void consumeOrderCancelled_malformedPayloadIsNotAcknowledged() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "order.cancelled", 0, 0L, UUID.randomUUID().toString(), "{invalid"
        );

        assertThrows(IllegalArgumentException.class,
                () -> consumer.consumeOrderCancelled(record, acknowledgment));
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    @DisplayName("Negative: missing eventId is not acknowledged")
    void consumeOrderCancelled_missingEventIdIsNotAcknowledged() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "order.cancelled", 0, 0L, UUID.randomUUID().toString(),
                "{\"eventType\":\"order.cancelled\",\"orderId\":\"" + UUID.randomUUID()
                        + "\",\"refundRequired\":false}"
        );

        assertThrows(IllegalArgumentException.class,
                () -> consumer.consumeOrderCancelled(record, acknowledgment));
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    @DisplayName("Negative: unsupported event version is not acknowledged")
    void consumePaymentLateSuccess_unsupportedVersionIsNotAcknowledged() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "payment.late-success", 0, 0L, UUID.randomUUID().toString(),
                "{\"eventId\":\"" + UUID.randomUUID()
                        + "\",\"eventType\":\"payment.late-success\",\"eventVersion\":2,\"orderId\":\""
                        + UUID.randomUUID() + "\"}"
        );

        assertThrows(IllegalArgumentException.class,
                () -> consumer.consumePaymentLateSuccess(record, acknowledgment));
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    @DisplayName("Positive: inventory.reservation-commit-failed triggers auto refund compensation and acknowledges")
    void consumeReservationCommitFailed_triggersCompensation() throws Exception {
        UUID orderId = UUID.randomUUID();
        ReservationCommitFailedEvent event = ReservationCommitFailedEvent.builder()
                .eventId(UUID.randomUUID())
                .orderId(orderId)
                .reservationId(UUID.randomUUID())
                .reason("Stock out of sync")
                .correlationId("CORR_2")
                .build();

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "inventory.reservation-commit-failed", 0, 0L, orderId.toString(), objectMapper.writeValueAsString(event)
        );

        consumer.consumeReservationCommitFailed(record, acknowledgment);

        verify(refundService, times(1)).processAutoRefund(eq(orderId), isNull(), contains("Stock out of sync"), eq("CORR_2"));
        verify(acknowledgment, times(1)).acknowledge();
    }

    @Test
    @DisplayName("Positive: payment.late-success triggers idempotent auto-refund and acknowledges")
    void consumePaymentLateSuccess_triggersCompensation() throws Exception {
        UUID orderId = UUID.randomUUID();
        PaymentLateSuccessEvent event = PaymentLateSuccessEvent.builder()
                .eventId(UUID.randomUUID())
                .orderId(orderId)
                .correlationId("CORR_LATE")
                .lateSuccessReason("LATE_SUCCESS_AFTER_EXPIRED")
                .build();
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "payment.late-success", 0, 0L, orderId.toString(), objectMapper.writeValueAsString(event)
        );

        consumer.consumePaymentLateSuccess(record, acknowledgment);

        verify(refundService).processAutoRefund(eq(orderId), isNull(), contains("LATE_SUCCESS"), eq("CORR_LATE"));
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("Positive: inventory.reservation-expired transitions PENDING payment to EXPIRED and acknowledges")
    void consumeReservationExpired_transitionsPendingToExpired() throws Exception {
        UUID orderId = UUID.randomUUID();
        ReservationExpiredEvent event = ReservationExpiredEvent.builder()
                .eventId(UUID.randomUUID())
                .orderId(orderId)
                .reservationId(UUID.randomUUID())
                .build();

        Payment payment = Payment.builder()
                .id(UUID.randomUUID())
                .orderId(orderId)
                .status(PaymentStatus.PENDING)
                .build();

        when(paymentRepository.findByOrderIdForUpdate(orderId)).thenReturn(Optional.of(payment));

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "inventory.reservation-expired", 0, 0L, orderId.toString(), objectMapper.writeValueAsString(event)
        );

        consumer.consumeReservationExpired(record, acknowledgment);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        verify(paymentRepository, times(1)).save(payment);
        verify(auditLogRepository, times(1)).save(any(PaymentAuditLog.class));
        verify(acknowledgment, times(1)).acknowledge();
    }

    private static org.assertj.core.api.AbstractAssert<?, ?> assertThat(Object actual) {
        return org.assertj.core.api.Assertions.assertThat(actual);
    }
}
