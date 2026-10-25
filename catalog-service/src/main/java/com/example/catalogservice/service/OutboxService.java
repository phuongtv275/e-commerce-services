package com.example.catalogservice.service;

import com.ecommerce.common.event.CatalogProductChangedEvent;
import com.ecommerce.common.outbox.OutboxEvent;
import com.ecommerce.common.outbox.OutboxEventRepository;
import com.ecommerce.common.outbox.OutboxStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordProductEvent(UUID productId, String action, String name, String status, Long revision) {
        CatalogProductChangedEvent domainEvent = CatalogProductChangedEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType("catalog.product.changed")
                .eventVersion(1)
                .occurredAt(Instant.now())
                .correlationId(MDC.get("correlationId"))
                .producer("catalog-service")
                .productId(productId)
                .aggregateRevision(revision != null ? revision : Instant.now().toEpochMilli())
                .name(name)
                .status(status)
                .action(action)
                .build();

        try {
            String payload = objectMapper.writeValueAsString(domainEvent);
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .id(UUID.randomUUID())
                    .aggregateType("PRODUCT")
                    .aggregateId(productId.toString())
                    .eventType("catalog.product.changed")
                    .eventVersion(1)
                    .payload(payload)
                    .status(OutboxStatus.PENDING)
                    .retryCount(0)
                    .createdAt(Instant.now())
                    .correlationId(MDC.get("correlationId"))
                    .build();

            outboxEventRepository.save(outboxEvent);
            log.info("Recorded Outbox event for product {} action {}", productId, action);
        } catch (Exception e) {
            log.error("Failed to serialize CatalogProductChangedEvent for outbox: {}", e.getMessage(), e);
            throw new RuntimeException("Could not record outbox event", e);
        }
    }
}
