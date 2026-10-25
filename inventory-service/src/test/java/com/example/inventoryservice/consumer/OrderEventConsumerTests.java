package com.example.inventoryservice.consumer;

import com.ecommerce.common.event.OrderCancelledEvent;
import com.ecommerce.common.idempotency.IdempotentConsumerHelper;
import com.example.inventoryservice.entity.InventoryQuarantineLog;
import com.example.inventoryservice.repository.InventoryQuarantineLogRepository;
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

import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderEventConsumerTests {

    @Mock
    private ReservationService reservationService;

    @Mock
    private IdempotentConsumerHelper idempotentConsumerHelper;

    @Mock
    private InventoryQuarantineLogRepository quarantineLogRepository;

    @Mock
    private Acknowledgment acknowledgment;

    private ObjectMapper objectMapper;
    private OrderEventConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        consumer = new OrderEventConsumer(
                reservationService,
                idempotentConsumerHelper,
                quarantineLogRepository,
                objectMapper
        );
    }

    @Test
    @DisplayName("Positive: order.cancelled invokes restockForEvent and acknowledges message")
    void testOrderCancelled_RestocksAndAcks() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        OrderCancelledEvent event = OrderCancelledEvent.builder()
                .eventId(eventId)
                .orderId(orderId)
                .reason("Buyer cancelled order")
                .refundRequired(true)
                .cancelledBy("buyer")
                .correlationId("corr-cancel-1")
                .occurredAt(Instant.now())
                .build();

        String payload = objectMapper.writeValueAsString(event);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("order.cancelled", 0, 0, orderId.toString(), payload);

        doAnswer(invocation -> {
            Consumer<OrderCancelledEvent> logic = invocation.getArgument(3);
            OrderCancelledEvent evt = invocation.getArgument(2);
            logic.accept(evt);
            return true;
        }).when(idempotentConsumerHelper).processIdempotent(eq(eventId.toString()), any(), any(), any());

        consumer.consumeOrderCancelled(record, acknowledgment);

        verify(reservationService).restockForEvent(orderId, "Buyer cancelled order", "corr-cancel-1");
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("Positive: Duplicate order.cancelled is skipped by idempotent helper and acknowledged")
    void testOrderCancelled_Duplicate_SkippedAndAcked() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        OrderCancelledEvent event = OrderCancelledEvent.builder()
                .eventId(eventId)
                .orderId(orderId)
                .correlationId("corr-cancel-dup")
                .build();

        String payload = objectMapper.writeValueAsString(event);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("order.cancelled", 0, 0, orderId.toString(), payload);

        when(idempotentConsumerHelper.processIdempotent(eq(eventId.toString()), any(), any(), any()))
                .thenReturn(false);

        consumer.consumeOrderCancelled(record, acknowledgment);

        verify(reservationService, never()).restockForEvent(any(), any(), any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("Negative: Malformed order.cancelled payload is quarantined and acknowledged")
    void testOrderCancelled_MalformedPayload_QuarantinedAndAcked() {
        String badPayload = "{ invalid json payload }";
        ConsumerRecord<String, String> record = new ConsumerRecord<>("order.cancelled", 0, 0, "bad-key", badPayload);

        consumer.consumeOrderCancelled(record, acknowledgment);

        ArgumentCaptor<InventoryQuarantineLog> captor = ArgumentCaptor.forClass(InventoryQuarantineLog.class);
        verify(quarantineLogRepository).save(captor.capture());
        assertThat(captor.getValue().getErrorReason()).isEqualTo("MALFORMED_PAYLOAD_ORDER_CANCELLED");
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("Negative: When saving malformed payload to quarantine fails, exception is thrown and message is not acknowledged")
    void testOrderCancelled_MalformedPayload_QuarantineSaveFails_ThrowsWithoutAck() {
        String badPayload = "{ bad json }";
        ConsumerRecord<String, String> record = new ConsumerRecord<>("order.cancelled", 0, 0, "bad-key", badPayload);

        doThrow(new RuntimeException("Database error saving quarantine log"))
                .when(quarantineLogRepository).save(any(InventoryQuarantineLog.class));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> consumer.consumeOrderCancelled(record, acknowledgment))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Database error saving quarantine log");

        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    @DisplayName("Negative: Payload with missing orderId or eventId or wrong eventType is quarantined and acknowledged")
    void testOrderCancelled_InvalidFields_QuarantinedAndAcked() {
        // Missing orderId
        String invalidPayload = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"order.cancelled\"}";
        ConsumerRecord<String, String> record = new ConsumerRecord<>("order.cancelled", 0, 0, "some-key", invalidPayload);

        consumer.consumeOrderCancelled(record, acknowledgment);

        verify(quarantineLogRepository).save(any(InventoryQuarantineLog.class));
        verify(acknowledgment).acknowledge();
    }
}
