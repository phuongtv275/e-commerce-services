package com.example.identityservice.outbox;

import com.ecommerce.common.constant.SecurityConstants;
import com.ecommerce.common.outbox.OutboxEvent;
import com.ecommerce.common.outbox.OutboxEventRepository;
import com.ecommerce.common.outbox.OutboxPublisher;
import com.ecommerce.common.outbox.OutboxStatus;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class OutboxKafkaPublishingIntegrationTests {

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired(required = false)
    private OutboxPublisher outboxPublisher;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
    }

    @Test
    @DisplayName("Context: OutboxPublisher bean is successfully instantiated and autowired")
    void testOutboxPublisherBeanLoaded() {
        assertThat(outboxPublisher).isNotNull();
    }

    @Test
    @DisplayName("POS-OUTBOX-01: OutboxPublisher publishes user.registered event to Kafka and updates status to PUBLISHED")
    void testPublishUserRegisteredEvent() {
        // Arrange
        UUID eventId = UUID.randomUUID();
        String correlationId = "corr-test-12345";
        OutboxEvent event = OutboxEvent.builder()
                .id(eventId)
                .aggregateType("USER")
                .aggregateId("101")
                .eventType("user.registered")
                .eventVersion(1)
                .payload("{\"userId\":101,\"email\":\"user@example.com\",\"fullName\":\"Nguyen Van A\"}")
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .createdAt(Instant.now())
                .correlationId(correlationId)
                .build();
        outboxEventRepository.save(event);

        SendResult<String, String> sendResult = mock(SendResult.class);
        RecordMetadata metadata = new RecordMetadata(new TopicPartition("user.registered", 0), 0, 0, System.currentTimeMillis(), 0, 0);
        when(sendResult.getRecordMetadata()).thenReturn(metadata);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(sendResult));

        // Act
        int processed = outboxPublisher.publishPendingEvents();

        // Assert
        assertThat(processed).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());

        ProducerRecord<String, String> sentRecord = captor.getValue();
        assertThat(sentRecord.topic()).isEqualTo("user.registered");
        assertThat(sentRecord.key()).isEqualTo("101");
        assertThat(sentRecord.value()).contains("user@example.com");

        Header corrHeader = sentRecord.headers().lastHeader(SecurityConstants.HEADER_CORRELATION_ID);
        assertThat(corrHeader).isNotNull();
        assertThat(new String(corrHeader.value(), StandardCharsets.UTF_8)).isEqualTo(correlationId);

        Header typeHeader = sentRecord.headers().lastHeader("X-Event-Type");
        assertThat(typeHeader).isNotNull();
        assertThat(new String(typeHeader.value(), StandardCharsets.UTF_8)).isEqualTo("user.registered");

        OutboxEvent updated = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(updated.getPublishedAt()).isNotNull();
        assertThat(updated.getLastError()).isNull();
    }

    @Test
    @DisplayName("POS-OUTBOX-02: OutboxPublisher publishes user.password-reset-requested event to Kafka")
    void testPublishPasswordResetRequestedEvent() {
        // Arrange
        UUID eventId = UUID.randomUUID();
        String correlationId = "corr-reset-789";
        OutboxEvent event = OutboxEvent.builder()
                .id(eventId)
                .aggregateType("USER")
                .aggregateId("202")
                .eventType("user.password-reset-requested")
                .eventVersion(1)
                .payload("{\"userId\":202,\"email\":\"reset@example.com\",\"resetToken\":\"raw-token-abc\"}")
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .createdAt(Instant.now())
                .correlationId(correlationId)
                .build();
        outboxEventRepository.save(event);

        SendResult<String, String> sendResult = mock(SendResult.class);
        RecordMetadata metadata = new RecordMetadata(new TopicPartition("user.password-reset-requested", 0), 0, 0, System.currentTimeMillis(), 0, 0);
        when(sendResult.getRecordMetadata()).thenReturn(metadata);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(sendResult));

        // Act
        int processed = outboxPublisher.publishPendingEvents();

        // Assert
        assertThat(processed).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());

        ProducerRecord<String, String> sentRecord = captor.getValue();
        assertThat(sentRecord.topic()).isEqualTo("user.password-reset-requested");
        assertThat(sentRecord.key()).isEqualTo("202");
        assertThat(sentRecord.value()).contains("raw-token-abc");

        OutboxEvent updated = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(updated.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("NEG-OUTBOX-01: When KafkaTemplate fails to send, OutboxPublisher marks event as FAILED_RETRYABLE")
    void testPublishFailureSetsRetryable() {
        // Arrange
        UUID eventId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.builder()
                .id(eventId)
                .aggregateType("USER")
                .aggregateId("303")
                .eventType("user.registered")
                .eventVersion(1)
                .payload("{\"userId\":303}")
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .createdAt(Instant.now())
                .build();
        outboxEventRepository.save(event);

        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new TimeoutException("Kafka broker unreachable"));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failedFuture);

        // Act
        int processed = outboxPublisher.publishPendingEvents();

        // Assert
        assertThat(processed).isEqualTo(1);

        OutboxEvent updated = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(OutboxStatus.FAILED_RETRYABLE);
        assertThat(updated.getRetryCount()).isEqualTo(1);
        assertThat(updated.getLastError()).contains("Kafka broker unreachable");
        assertThat(updated.getNextRetryAt()).isNotNull();
        assertThat(updated.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("NEG-OUTBOX-02: When retry limit reached, OutboxPublisher marks event as DEAD_LETTER")
    void testPublishFailureExceedsMaxRetriesMarksDeadLetter() {
        // Arrange
        UUID eventId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.builder()
                .id(eventId)
                .aggregateType("USER")
                .aggregateId("404")
                .eventType("user.registered")
                .eventVersion(1)
                .payload("{\"userId\":404}")
                .status(OutboxStatus.FAILED_RETRYABLE)
                .retryCount(9) // retryCount will reach 10 on failure
                .createdAt(Instant.now())
                .build();
        outboxEventRepository.save(event);

        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Fatal broker error"));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failedFuture);

        // Act
        int processed = outboxPublisher.publishPendingEvents();

        // Assert
        assertThat(processed).isEqualTo(1);

        OutboxEvent updated = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(OutboxStatus.DEAD_LETTER);
        assertThat(updated.getRetryCount()).isEqualTo(10);
        assertThat(updated.getLastError()).contains("Fatal broker error");
    }
}
