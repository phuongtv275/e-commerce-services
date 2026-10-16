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
            // Giành quyền xử lý (claim) trước bằng UNIQUE constraint (event_id, consumer_group)
            ProcessedEvent processedRecord = ProcessedEvent.builder()
                    .id(UUID.randomUUID())
                    .eventId(eventId)
                    .consumerGroup(consumerGroup)
                    .processedAt(Instant.now())
                    .build();

            processedEventRepository.saveAndFlush(processedRecord);
        } catch (DataIntegrityViolationException e) {
            log.warn("Concurrent duplicate execution detected for event id={} in consumer group {}. Claim failed, skipping logic.",
                    eventId, consumerGroup);
            return false;
        }

        // Chỉ khi claim thành công mới thực thi nghiệp vụ
        consumerLogic.accept(payload);
        log.debug("Successfully processed event id={} for consumer group {}", eventId, consumerGroup);
        return true;
    }
}
