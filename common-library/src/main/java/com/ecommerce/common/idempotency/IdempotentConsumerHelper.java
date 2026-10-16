package com.ecommerce.common.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotentConsumerHelper {

    private final ProcessedEventRepository processedEventRepository;

    @Transactional
    public <T> boolean processIdempotent(String eventId, String consumerGroup, T payload, Consumer<T> consumerLogic) {
        if (processedEventRepository.existsByEventIdAndConsumerGroup(eventId, consumerGroup)) {
            log.info("Event id={} was already processed by consumer group {}. Skipping duplicate message.",
                    eventId, consumerGroup);
            return false;
        }

        try {
            consumerLogic.accept(payload);

            ProcessedEvent processedRecord = ProcessedEvent.builder()
                    .id(UUID.randomUUID())
                    .eventId(eventId)
                    .consumerGroup(consumerGroup)
                    .processedAt(Instant.now())
                    .build();

            processedEventRepository.saveAndFlush(processedRecord);
            log.debug("Marked event id={} as processed for consumer group {}", eventId, consumerGroup);
            return true;

        } catch (DataIntegrityViolationException e) {
            log.warn("Concurrent duplicate execution detected for event id={} in consumer group {}. Ignored gracefully.",
                    eventId, consumerGroup);
            return false;
        }
    }
}
