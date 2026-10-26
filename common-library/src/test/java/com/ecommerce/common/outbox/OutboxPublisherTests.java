package com.ecommerce.common.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTests {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxPublisher outboxPublisher;

    @BeforeEach
    void setUp() {
        outboxPublisher = new OutboxPublisher(outboxEventRepository, kafkaTemplate);
    }

    @Test
    @DisplayName("Positive: Xuất bản thành công event PENDING lên Kafka và chuyển trạng thái sang PUBLISHED")
    void testPublishPendingEvent_Success_Positive() {
        OutboxEvent event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("ORDER")
                .aggregateId("order-101")
                .eventType("order.created")
                .eventVersion(1)
                .payload("{\"orderId\":\"order-101\"}")
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .correlationId("corr-publish-1")
                .build();

        when(outboxEventRepository.findPendingEventsForPublishing(any(), any(), any(Pageable.class)))
                .thenReturn(List.of(event));

        RecordMetadata metadata = new RecordMetadata(new TopicPartition("order.created", 0), 0, 0, 0, 0, 0);
        SendResult<String, String> sendResult = new SendResult<>(null, metadata);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        int published = outboxPublisher.publishPendingEvents();

        assertEquals(1, published);
        assertEquals(OutboxStatus.PUBLISHED, event.getStatus());
        assertNotNull(event.getPublishedAt());
        assertNull(event.getLastError());
        verify(outboxEventRepository).save(event);

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, String> captured = captor.getValue();
        assertNotNull(captured.headers().lastHeader("X-Event-Type"));
        assertEquals("order.created", new String(captured.headers().lastHeader("X-Event-Type").value()));
        assertNotNull(captured.headers().lastHeader("X-Event-Id"));
        assertEquals(event.getId().toString(), new String(captured.headers().lastHeader("X-Event-Id").value()));
        assertNotNull(captured.headers().lastHeader("X-Correlation-Id"));
        assertEquals("corr-publish-1", new String(captured.headers().lastHeader("X-Correlation-Id").value()));
    }

    @Test
    @DisplayName("Negative: Kafka gặp lỗi kết nối chuyển trạng thái sang FAILED_RETRYABLE và tăng retryCount")
    void testKafkaFailure_Retryable_Negative() {
        OutboxEvent event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("ORDER")
                .aggregateId("order-102")
                .eventType("order.created")
                .eventVersion(1)
                .payload("{\"orderId\":\"order-102\"}")
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .build();

        when(outboxEventRepository.findPendingEventsForPublishing(any(), any(), any(Pageable.class)))
                .thenReturn(List.of(event));

        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka broker down"));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failedFuture);

        int processed = outboxPublisher.publishPendingEvents();

        assertEquals(1, processed);
        assertEquals(OutboxStatus.FAILED_RETRYABLE, event.getStatus());
        assertEquals(1, event.getRetryCount());
        assertNotNull(event.getNextRetryAt());
        assertTrue(event.getLastError().contains("Kafka broker down"));
        verify(outboxEventRepository).save(event);
    }

    @Test
    @DisplayName("Negative: Đạt ngưỡng 10 lần retry thất bại chuyển trạng thái sang DEAD_LETTER")
    void testMaxRetries_TransitionsToDeadLetter_Negative() {
        OutboxEvent event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("PAYMENT")
                .aggregateId("pay-999")
                .eventType("payment.succeeded")
                .eventVersion(1)
                .payload("{\"paymentId\":\"pay-999\"}")
                .status(OutboxStatus.FAILED_RETRYABLE)
                .retryCount(9) // Lần thử thứ 10 sắp tới
                .build();

        when(outboxEventRepository.findPendingEventsForPublishing(any(), any(), any(Pageable.class)))
                .thenReturn(List.of(event));

        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Persistent serialization error"));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failedFuture);

        outboxPublisher.publishPendingEvents();

        assertEquals(OutboxStatus.DEAD_LETTER, event.getStatus());
        assertEquals(10, event.getRetryCount());
        verify(outboxEventRepository).save(event);
    }
}
