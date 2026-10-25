package com.example.inventoryservice.consumer;

import com.ecommerce.common.event.PaymentFailedEvent;
import com.ecommerce.common.event.PaymentSucceededEvent;
import com.ecommerce.common.exception.ConflictException;
import com.ecommerce.common.idempotency.IdempotentConsumerHelper;
import com.example.inventoryservice.entity.InventoryQuarantineLog;
import com.example.inventoryservice.entity.Reservation;
import com.example.inventoryservice.entity.ReservationStatus;
import com.example.inventoryservice.repository.InventoryQuarantineLogRepository;
import com.example.inventoryservice.repository.ReservationRepository;
import com.example.inventoryservice.service.ReservationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentEventConsumerTests {

    @Mock
    private ReservationService reservationService;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private IdempotentConsumerHelper idempotentConsumerHelper;

    @Mock
    private InventoryQuarantineLogRepository quarantineLogRepository;

    @Mock
    private Acknowledgment acknowledgment;

    private ObjectMapper objectMapper;
    private PaymentEventConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        consumer = new PaymentEventConsumer(
                reservationService,
                reservationRepository,
                idempotentConsumerHelper,
                quarantineLogRepository,
                objectMapper
        );
    }

    @Test
    @DisplayName("Positive: payment.succeeded on PENDING reservation commits stock and acknowledges message")
    void testPaymentSucceeded_PendingReservation_CommitsAndAcks() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        PaymentSucceededEvent event = PaymentSucceededEvent.builder()
                .eventId(eventId)
                .orderId(orderId)
                .paymentId(UUID.randomUUID())
                .amount(new BigDecimal("100.00"))
                .currency("VND")
                .paidAt(Instant.now())
                .correlationId("corr-pay-1")
                .build();

        String payload = objectMapper.writeValueAsString(event);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment.succeeded", 0, 0, orderId.toString(), payload);

        Reservation reservation = Reservation.builder()
                .orderId(orderId)
                .status(ReservationStatus.PENDING)
                .build();
        when(reservationRepository.findByOrderId(orderId)).thenReturn(Optional.of(reservation));

        doAnswer(invocation -> {
            Consumer<PaymentSucceededEvent> logic = invocation.getArgument(3);
            PaymentSucceededEvent evt = invocation.getArgument(2);
            logic.accept(evt);
            return true;
        }).when(idempotentConsumerHelper).processIdempotent(eq(eventId.toString()), any(), any(), any());

        consumer.consumePaymentSucceeded(record, acknowledgment, 1);

        verify(reservationService).commit(orderId, "corr-pay-1");
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("Positive: Duplicate payment.succeeded is skipped by idempotent helper and acknowledged")
    void testPaymentSucceeded_Duplicate_SkippedAndAcked() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        PaymentSucceededEvent event = PaymentSucceededEvent.builder()
                .eventId(eventId)
                .orderId(orderId)
                .paymentId(UUID.randomUUID())
                .correlationId("corr-dup")
                .build();

        String payload = objectMapper.writeValueAsString(event);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment.succeeded", 0, 0, orderId.toString(), payload);

        // Idempotent helper returns false (already processed)
        when(idempotentConsumerHelper.processIdempotent(eq(eventId.toString()), any(), any(), any()))
                .thenReturn(false);

        consumer.consumePaymentSucceeded(record, acknowledgment, 1);

        verify(reservationService, never()).commit(any(), any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("Positive: Late payment.succeeded on already COMMITTED reservation is idempotent no-op and acked")
    void testPaymentSucceeded_AlreadyCommitted_NoOpAndAcked() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        PaymentSucceededEvent event = PaymentSucceededEvent.builder()
                .eventId(eventId)
                .orderId(orderId)
                .paymentId(UUID.randomUUID())
                .correlationId("corr-late")
                .build();

        String payload = objectMapper.writeValueAsString(event);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment.succeeded", 0, 0, orderId.toString(), payload);

        Reservation reservation = Reservation.builder()
                .orderId(orderId)
                .status(ReservationStatus.COMMITTED)
                .build();
        when(reservationRepository.findByOrderId(orderId)).thenReturn(Optional.of(reservation));

        doAnswer(invocation -> {
            Consumer<PaymentSucceededEvent> logic = invocation.getArgument(3);
            PaymentSucceededEvent evt = invocation.getArgument(2);
            logic.accept(evt);
            return true;
        }).when(idempotentConsumerHelper).processIdempotent(eq(eventId.toString()), any(), any(), any());

        consumer.consumePaymentSucceeded(record, acknowledgment, 1);

        verify(reservationService, never()).commit(any(), any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("Negative: Missing reservation on attempt 1 throws exception to trigger Kafka retry without ack")
    void testPaymentSucceeded_MissingReservation_ThrowsForRetry() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        PaymentSucceededEvent event = PaymentSucceededEvent.builder()
                .eventId(eventId)
                .orderId(orderId)
                .paymentId(UUID.randomUUID())
                .correlationId("corr-retry")
                .build();

        String payload = objectMapper.writeValueAsString(event);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment.succeeded", 0, 0, orderId.toString(), payload);

        when(reservationRepository.findByOrderId(orderId)).thenReturn(Optional.empty());

        doAnswer(invocation -> {
            Consumer<PaymentSucceededEvent> logic = invocation.getArgument(3);
            PaymentSucceededEvent evt = invocation.getArgument(2);
            logic.accept(evt);
            return true;
        }).when(idempotentConsumerHelper).processIdempotent(eq(eventId.toString()), any(), any(), any());

        assertThatThrownBy(() -> consumer.consumePaymentSucceeded(record, acknowledgment, 1))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Reservation not found for orderId");

        verify(acknowledgment, never()).acknowledge();
        verify(reservationService, never()).quarantineCommitFailure(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Negative: Missing reservation after retry exhaustion (attempt >= 3) records quarantine and acks")
    void testPaymentSucceeded_MissingReservation_ExhaustedRetriesQuarantined() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        PaymentSucceededEvent event = PaymentSucceededEvent.builder()
                .eventId(eventId)
                .orderId(orderId)
                .paymentId(UUID.randomUUID())
                .correlationId("corr-exhaust")
                .build();

        String payload = objectMapper.writeValueAsString(event);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment.succeeded", 0, 0, orderId.toString(), payload);

        when(reservationRepository.findByOrderId(orderId)).thenReturn(Optional.empty());

        doAnswer(invocation -> {
            Consumer<PaymentSucceededEvent> logic = invocation.getArgument(3);
            PaymentSucceededEvent evt = invocation.getArgument(2);
            logic.accept(evt);
            return true;
        }).when(idempotentConsumerHelper).processIdempotent(eq(eventId.toString()), any(), any(), any());

        // Attempt 3: exhausted
        consumer.consumePaymentSucceeded(record, acknowledgment, 3);

        verify(reservationService).quarantineCommitFailure(eq(orderId), eq("RESERVATION_NOT_FOUND_AFTER_PAYMENT"), eq(payload), eq("corr-exhaust"));
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("Negative: Malformed payload is quarantined and acknowledged to avoid blocking partition")
    void testPaymentSucceeded_MalformedPayload_QuarantinedAndAcked() {
        String badPayload = "{ this is not valid json }";
        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment.succeeded", 0, 0, "bad-key", badPayload);

        consumer.consumePaymentSucceeded(record, acknowledgment, 1);

        ArgumentCaptor<InventoryQuarantineLog> captor = ArgumentCaptor.forClass(InventoryQuarantineLog.class);
        verify(quarantineLogRepository).save(captor.capture());
        assertThat(captor.getValue().getErrorReason()).isEqualTo("MALFORMED_PAYLOAD_PAYMENT_SUCCEEDED");
        assertThat(captor.getValue().getPayload()).isEqualTo(badPayload);
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("Negative: When saving malformed payload to quarantine fails, exception is thrown and message is not acknowledged")
    void testPaymentSucceeded_MalformedPayload_QuarantineSaveFails_ThrowsWithoutAck() {
        String badPayload = "{ invalid json }";
        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment.succeeded", 0, 0, "bad-key", badPayload);

        doThrow(new RuntimeException("Database error saving quarantine log"))
                .when(quarantineLogRepository).save(any(InventoryQuarantineLog.class));

        assertThatThrownBy(() -> consumer.consumePaymentSucceeded(record, acknowledgment, 1))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Database error saving quarantine log");

        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    @DisplayName("Negative: Payload with missing orderId or eventId or wrong eventType is quarantined and acknowledged")
    void testPaymentSucceeded_InvalidFields_QuarantinedAndAcked() {
        // Missing orderId and paymentId
        String invalidPayload = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"order.cancelled\"}";
        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment.succeeded", 0, 0, "some-key", invalidPayload);

        consumer.consumePaymentSucceeded(record, acknowledgment, 1);

        verify(quarantineLogRepository).save(any(InventoryQuarantineLog.class));
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("Negative: When quarantineCommitFailure throws on attempt 3, exception propagates without ack")
    void testPaymentSucceeded_ExhaustedRetry_QuarantineFails_ThrowsWithoutAck() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        PaymentSucceededEvent event = PaymentSucceededEvent.builder()
                .eventId(eventId)
                .orderId(orderId)
                .paymentId(UUID.randomUUID())
                .correlationId("corr-quarantine-fail")
                .build();

        String payload = "";
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (Exception ignored) {}
        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment.succeeded", 0, 0, orderId.toString(), payload);

        when(reservationRepository.findByOrderId(orderId)).thenReturn(Optional.empty());
        doThrow(new RuntimeException("Outbox/Quarantine DB transaction failed"))
                .when(reservationService).quarantineCommitFailure(any(), any(), any(), any());

        doAnswer(invocation -> {
            Consumer<PaymentSucceededEvent> logic = invocation.getArgument(3);
            PaymentSucceededEvent evt = invocation.getArgument(2);
            logic.accept(evt);
            return true;
        }).when(idempotentConsumerHelper).processIdempotent(eq(eventId.toString()), any(), any(), any());

        assertThatThrownBy(() -> consumer.consumePaymentSucceeded(record, acknowledgment, 3))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Outbox/Quarantine DB transaction failed");

        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    @DisplayName("Positive: payment.failed invokes releaseForEvent and acknowledges message")
    void testPaymentFailed_ReleasesAndAcks() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        PaymentFailedEvent event = PaymentFailedEvent.builder()
                .eventId(eventId)
                .orderId(orderId)
                .failureReason("INSUFFICIENT_FUNDS")
                .correlationId("corr-fail-1")
                .build();

        String payload = objectMapper.writeValueAsString(event);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment.failed", 0, 0, orderId.toString(), payload);

        doAnswer(invocation -> {
            Consumer<PaymentFailedEvent> logic = invocation.getArgument(3);
            PaymentFailedEvent evt = invocation.getArgument(2);
            logic.accept(evt);
            return true;
        }).when(idempotentConsumerHelper).processIdempotent(eq(eventId.toString()), any(), any(), any());

        consumer.consumePaymentFailed(record, acknowledgment);

        verify(reservationService).releaseForEvent(orderId, "INSUFFICIENT_FUNDS", "corr-fail-1");
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("Negative: payment.failed with missing orderId is quarantined and acknowledged")
    void testPaymentFailed_MissingOrderId_QuarantinedAndAcked() {
        String invalidPayload = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"payment.failed\"}";
        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment.failed", 0, 0, "fail-key", invalidPayload);

        consumer.consumePaymentFailed(record, acknowledgment);

        verify(quarantineLogRepository).save(any(InventoryQuarantineLog.class));
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("Negative: When quarantine save fails on payment.failed, exception is thrown without ack")
    void testPaymentFailed_QuarantineSaveFails_ThrowsWithoutAck() {
        String badPayload = "{ bad json }";
        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment.failed", 0, 0, "bad-key", badPayload);

        doThrow(new RuntimeException("DB save quarantine failed"))
                .when(quarantineLogRepository).save(any(InventoryQuarantineLog.class));

        assertThatThrownBy(() -> consumer.consumePaymentFailed(record, acknowledgment))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB save quarantine failed");

        verify(acknowledgment, never()).acknowledge();
    }
}
