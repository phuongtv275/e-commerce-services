package com.ecommerce.common.outbox;

import com.ecommerce.common.constant.SecurityConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.MDC;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private static final int BATCH_SIZE = 50;
    private static final int MAX_RETRIES = 10;
    private static final long MAX_BACKOFF_MILLIS = 300000L; // 5 phút

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelayString = "${outbox.publisher.interval-ms:3000}")
    public int publishPendingEvents() {
        List<OutboxStatus> eligibleStatuses = List.of(OutboxStatus.PENDING, OutboxStatus.FAILED_RETRYABLE);
        Instant now = Instant.now();

        List<OutboxEvent> events = outboxEventRepository.findPendingEventsForPublishing(
                eligibleStatuses,
                now,
                PageRequest.of(0, BATCH_SIZE)
        );

        if (events.isEmpty()) {
            return 0;
        }

        log.debug("Found {} outbox events to publish", events.size());
        int processedCount = 0;

        for (OutboxEvent event : events) {
            publishSingleEvent(event);
            processedCount++;
        }

        return processedCount;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishSingleEvent(OutboxEvent event) {
        String correlationId = event.getCorrelationId();
        if (correlationId != null) {
            MDC.put(SecurityConstants.MDC_CORRELATION_ID, correlationId);
        }

        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    event.getEventType(),
                    event.getAggregateId(),
                    event.getPayload()
            );

            if (correlationId != null) {
                record.headers().add(new RecordHeader(
                        SecurityConstants.HEADER_CORRELATION_ID,
                        correlationId.getBytes(StandardCharsets.UTF_8)
                ));
            }
            record.headers().add(new RecordHeader(
                    "X-Event-Type",
                    event.getEventType().getBytes(StandardCharsets.UTF_8)
            ));
            if (event.getId() != null) {
                record.headers().add(new RecordHeader(
                        "X-Event-Id",
                        event.getId().toString().getBytes(StandardCharsets.UTF_8)
                ));
            }

            // Chờ ACK từ Kafka để đảm bảo At-Least-Once Delivery bền vững
            kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);

            event.setStatus(OutboxStatus.PUBLISHED);
            event.setPublishedAt(Instant.now());
            event.setLastError(null);
            outboxEventRepository.save(event);

            log.info("Published outbox event id={}, aggregateType={}, aggregateId={}",
                    event.getId(), event.getAggregateType(), event.getAggregateId());

        } catch (Exception e) {
            int newRetry = event.getRetryCount() + 1;
            event.setRetryCount(newRetry);
            event.setLastError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());

            if (newRetry >= MAX_RETRIES) {
                event.setStatus(OutboxStatus.DEAD_LETTER);
                log.error("Outbox event id={} moved to DEAD_LETTER after {} retries! Last error: {}",
                        event.getId(), newRetry, e.getMessage(), e);
            } else {
                event.setStatus(OutboxStatus.FAILED_RETRYABLE);
                long backoffMillis = Math.min((long) Math.pow(2, newRetry) * 1000L, MAX_BACKOFF_MILLIS);
                event.setNextRetryAt(Instant.now().plusMillis(backoffMillis));
                log.warn("Failed to publish outbox event id={}, will retry in {} ms (attempt {}). Reason: {}",
                        event.getId(), backoffMillis, newRetry, e.getMessage());
            }

            outboxEventRepository.save(event);
        } finally {
            MDC.remove(SecurityConstants.MDC_CORRELATION_ID);
        }
    }
}
