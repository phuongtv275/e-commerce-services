package com.example.catalogservice.consumer;

import com.ecommerce.common.event.InventoryStockChangedEvent;
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
public class InventoryStockEventConsumer {

    private final CatalogIndexerService catalogIndexerService;
    private final IdempotentConsumerHelper idempotentConsumerHelper;
    private final ObjectMapper objectMapper;

    @Value("${spring.kafka.consumer.group-id:catalog-indexer-group}")
    private String consumerGroup;

    @KafkaListener(topics = "${catalog.kafka.topic.stock-changed:inventory.stock.changed}",
            groupId = "${spring.kafka.consumer.group-id:catalog-indexer-group}")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            InventoryStockChangedEvent event = objectMapper.readValue(record.value(), InventoryStockChangedEvent.class);
            String eventId = event.getEventId() != null ? event.getEventId().toString() : record.key();

            idempotentConsumerHelper.processIdempotent(eventId, consumerGroup, event, evt -> {
                log.info("Processing inventory.stock.changed event for SKU {}", evt.getSkuId());
                Long qty = evt.getAvailableQuantity() != null ? evt.getAvailableQuantity().longValue() : null;
                catalogIndexerService.syncStockToElasticsearch(evt.getSkuId(), qty, evt.getVersion(), evt.getInStock());
            });

            if (ack != null) {
                ack.acknowledge();
            }
        } catch (Exception e) {
            log.error("Failed to process inventory.stock.changed event: {}", e.getMessage(), e);
            throw new RuntimeException("Error processing inventory stock event", e);
        }
    }
}
