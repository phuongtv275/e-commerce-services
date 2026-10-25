package com.example.catalogservice.consumer;

import com.ecommerce.common.event.CatalogProductChangedEvent;
import com.ecommerce.common.idempotency.IdempotentConsumerHelper;
import com.example.catalogservice.elasticsearch.service.CatalogIndexerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogProductEventConsumer {

    private final CatalogIndexerService catalogIndexerService;
    private final IdempotentConsumerHelper idempotentConsumerHelper;
    private final ObjectMapper objectMapper;

    @Value("${spring.kafka.consumer.group-id:catalog-indexer-group}")
    private String consumerGroup;

    @KafkaListener(topics = "${catalog.kafka.topic.product-changed:catalog.product.changed}",
            groupId = "${spring.kafka.consumer.group-id:catalog-indexer-group}")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            CatalogProductChangedEvent event = objectMapper.readValue(record.value(), CatalogProductChangedEvent.class);
            String eventId = event.getEventId() != null ? event.getEventId().toString() : record.key();

            idempotentConsumerHelper.processIdempotent(eventId, consumerGroup, event, evt -> {
                log.info("Processing catalog.product.changed event for product {}", evt.getProductId());
                catalogIndexerService.syncProductToElasticsearch(evt.getProductId(), evt.getAggregateRevision());
            });

            if (ack != null) {
                ack.acknowledge();
            }
        } catch (Exception e) {
            log.error("Failed to process catalog.product.changed event: {}", e.getMessage(), e);
            throw new RuntimeException("Error processing catalog product event", e);
        }
    }
}
