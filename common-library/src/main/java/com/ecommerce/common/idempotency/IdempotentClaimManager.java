package com.ecommerce.common.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotentClaimManager {

    private final ProcessedEventRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(String eventId, String consumerGroup) {
        if (repository.existsByEventIdAndConsumerGroup(eventId, consumerGroup)) {
            return false;
        }

        try {
            ProcessedEvent record = ProcessedEvent.builder()
                    .id(UUID.randomUUID())
                    .eventId(eventId)
                    .consumerGroup(consumerGroup)
                    .processedAt(Instant.now())
                    .build();
            repository.saveAndFlush(record);
            return true;
        } catch (DataIntegrityViolationException e) {
            log.warn("Concurrent duplicate execution detected for event id={} in consumer group {}. Claim failed.",
                    eventId, consumerGroup);
            return false;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String eventId, String consumerGroup) {
        try {
            repository.findByEventIdAndConsumerGroup(eventId, consumerGroup)
                    .ifPresent(repository::delete);
        } catch (Exception e) {
            log.warn("Failed to release claim for event id={} in group {}: {}", eventId, consumerGroup, e.getMessage());
        }
    }
}
