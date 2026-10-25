package com.example.catalogservice.consumer;

import com.ecommerce.common.event.CatalogProductChangedEvent;
import com.ecommerce.common.event.InventoryStockChangedEvent;
import com.ecommerce.common.idempotency.IdempotentConsumerHelper;
import com.ecommerce.common.idempotency.ProcessedEvent;
import com.ecommerce.common.idempotency.ProcessedEventRepository;
import com.example.catalogservice.elasticsearch.service.CatalogIndexerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "management.health.elasticsearch.enabled=false",
        "spring.kafka.consumer.group-id=catalog-indexer-group"
})
class KafkaConsumerIdempotencyTests {

    @Autowired
    private CatalogProductEventConsumer productEventConsumer;

    @Autowired
    private InventoryStockEventConsumer stockEventConsumer;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CatalogIndexerService catalogIndexerService;

    @MockitoBean
    private com.example.catalogservice.elasticsearch.repository.ProductSkuRepository productSkuRepository;

    @MockitoBean
    private org.springframework.data.elasticsearch.core.ElasticsearchOperations elasticsearchOperations;

    @MockitoBean
    private com.example.catalogservice.elasticsearch.service.ElasticsearchIndexService elasticsearchIndexService;

    @BeforeEach
    void setUp() {
        processedEventRepository.deleteAll();
        reset(catalogIndexerService);
    }

    @AfterEach
    void tearDown() {
        processedEventRepository.deleteAll();
    }

    @Test
    @DisplayName("Positive: Product event consumer processes event and records in processed_events")
    void testProductConsumer_ProcessesAndRecordsIdempotency() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        CatalogProductChangedEvent event = CatalogProductChangedEvent.builder()
                .eventId(eventId)
                .productId(productId)
                .aggregateRevision(10L)
                .action("CREATED")
                .build();

        String json = objectMapper.writeValueAsString(event);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("catalog.product.changed", 0, 0, eventId.toString(), json);
        Acknowledgment ack = mock(Acknowledgment.class);

        productEventConsumer.consume(record, ack);

        verify(catalogIndexerService, times(1)).syncProductToElasticsearch(productId, 10L);
        verify(ack, times(1)).acknowledge();

        List<ProcessedEvent> processed = processedEventRepository.findAll();
        assertThat(processed).hasSize(1);
        assertThat(processed.get(0).getEventId()).isEqualTo(eventId.toString());
        assertThat(processed.get(0).getStatus().name()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("Positive: Duplicate product event delivery is skipped without re-invoking indexer")
    void testProductConsumer_DuplicateEvent_Skipped() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        CatalogProductChangedEvent event = CatalogProductChangedEvent.builder()
                .eventId(eventId)
                .productId(productId)
                .aggregateRevision(10L)
                .action("CREATED")
                .build();

        String json = objectMapper.writeValueAsString(event);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("catalog.product.changed", 0, 0, eventId.toString(), json);
        Acknowledgment ack = mock(Acknowledgment.class);

        // First delivery
        productEventConsumer.consume(record, ack);
        verify(catalogIndexerService, times(1)).syncProductToElasticsearch(productId, 10L);

        // Second duplicate delivery
        productEventConsumer.consume(record, ack);

        // Indexer must NOT be called a second time
        verify(catalogIndexerService, times(1)).syncProductToElasticsearch(productId, 10L);
        verify(ack, times(2)).acknowledge();
    }

    @Test
    @DisplayName("Positive: Stock event consumer processes event and ignores duplicates")
    void testStockConsumer_ProcessesAndSkipsDuplicate() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        InventoryStockChangedEvent event = InventoryStockChangedEvent.builder()
                .eventId(eventId)
                .skuId(skuId)
                .availableQuantity(25)
                .version(100L)
                .inStock(true)
                .build();

        String json = objectMapper.writeValueAsString(event);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("inventory.stock.changed", 0, 0, eventId.toString(), json);
        Acknowledgment ack = mock(Acknowledgment.class);

        // First delivery
        stockEventConsumer.consume(record, ack);
        verify(catalogIndexerService, times(1)).syncStockToElasticsearch(skuId, 25L, 100L, true);

        // Duplicate delivery
        stockEventConsumer.consume(record, ack);
        verify(catalogIndexerService, times(1)).syncStockToElasticsearch(skuId, 25L, 100L, true);
        verify(ack, times(2)).acknowledge();
    }

    @Test
    @DisplayName("Negative: Business logic exception releases claim to allow future retry")
    void testProductConsumer_ExceptionReleasesClaim() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        CatalogProductChangedEvent event = CatalogProductChangedEvent.builder()
                .eventId(eventId)
                .productId(productId)
                .build();

        String json = objectMapper.writeValueAsString(event);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("catalog.product.changed", 0, 0, eventId.toString(), json);
        Acknowledgment ack = mock(Acknowledgment.class);

        doThrow(new RuntimeException("Elasticsearch connection failed"))
                .when(catalogIndexerService).syncProductToElasticsearch(any(), any());

        assertThatThrownBy(() -> productEventConsumer.consume(record, ack))
                .isInstanceOf(RuntimeException.class);

        // Processed event record was deleted/released by IdempotentConsumerHelper
        List<ProcessedEvent> processed = processedEventRepository.findAll();
        assertThat(processed).isEmpty();
        verify(ack, never()).acknowledge();
    }
}
