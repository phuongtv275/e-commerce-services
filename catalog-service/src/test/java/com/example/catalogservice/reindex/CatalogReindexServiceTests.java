package com.example.catalogservice.reindex;

import com.ecommerce.common.exception.ResourceNotFoundException;
import com.example.catalogservice.dto.resp.ReindexJobResponse;
import com.example.catalogservice.elasticsearch.service.CatalogReindexService;
import com.example.catalogservice.elasticsearch.service.ElasticsearchIndexService;
import com.example.catalogservice.entity.*;
import com.example.catalogservice.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import com.ecommerce.common.outbox.OutboxEvent;
import com.ecommerce.common.outbox.OutboxEventRepository;
import com.example.catalogservice.elasticsearch.document.ProductSkuDocument;
import com.example.catalogservice.elasticsearch.repository.ProductSkuRepository;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CatalogReindexServiceTests {

    @Mock
    private CatalogReindexJobRepository reindexJobRepository;

    @Mock
    private ElasticsearchIndexService elasticsearchIndexService;

    @Mock
    private ElasticsearchOperations elasticsearchOperations;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private SkuRepository skuRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private BrandRepository brandRepository;

    @Mock
    private ProductImageRepository productImageRepository;

    @Mock
    private ProductSkuRepository productSkuRepository;

    @Mock
    private SkuStockSnapshotRepository skuStockSnapshotRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private CatalogReindexService reindexService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        reindexService = new CatalogReindexService(
                reindexJobRepository,
                elasticsearchIndexService,
                elasticsearchOperations,
                productRepository,
                skuRepository,
                categoryRepository,
                brandRepository,
                productImageRepository,
                productSkuRepository,
                skuStockSnapshotRepository,
                outboxEventRepository,
                objectMapper
        );
        ReflectionTestUtils.setField(reindexService, "defaultAliasName", "product_catalog");
    }

    @Test
    @DisplayName("Positive: createReindexJob initializes index, counts active SKUs, and records queued job")
    void testCreateReindexJob_Success() {
        UUID jobId = UUID.randomUUID();
        when(skuRepository.countByStatusAndDeletedAtIsNull(SkuStatus.ACTIVE))
                .thenReturn(15L);

        CatalogReindexJob savedJob = CatalogReindexJob.builder()
                .id(jobId)
                .status(ReindexJobStatus.QUEUED)
                .targetIndex("product_catalog-v12345")
                .totalDocuments(15L)
                .processedDocuments(0L)
                .failedDocuments(0L)
                .createdAt(Instant.now())
                .build();
        when(reindexJobRepository.save(any(CatalogReindexJob.class))).thenReturn(savedJob);

        ReindexJobResponse resp = reindexService.createReindexJob("admin-1");

        assertThat(resp).isNotNull();
        assertThat(resp.getId()).isEqualTo(jobId);
        assertThat(resp.getStatus()).isEqualTo(ReindexJobStatus.QUEUED);
        assertThat(resp.getTotalDocuments()).isEqualTo(15L);
        verify(elasticsearchIndexService).createPhysicalIndex(anyString());
    }

    @Test
    @DisplayName("Positive: executeReindex indexes products via keyset paging and atomically swaps alias upon completion")
    void testExecuteReindex_Success_SwapsAlias() {
        UUID jobId = UUID.randomUUID();
        String targetIndex = "product_catalog-v9999";

        CatalogReindexJob job = CatalogReindexJob.builder()
                .id(jobId)
                .status(ReindexJobStatus.QUEUED)
                .targetIndex(targetIndex)
                .totalDocuments(1L)
                .processedDocuments(0L)
                .failedDocuments(0L)
                .startedAt(Instant.now().minusSeconds(10))
                .build();
        when(reindexJobRepository.findById(jobId)).thenReturn(Optional.of(job));

        UUID categoryId = UUID.randomUUID();
        UUID brandId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        Category activeCat = Category.builder().id(categoryId).name("Tech").status(CategoryStatus.ACTIVE).build();
        when(categoryRepository.findByStatusOrderByDisplayOrderAscNameAscIdAsc(CategoryStatus.ACTIVE))
                .thenReturn(List.of(activeCat));

        Brand activeBrand = Brand.builder().id(brandId).name("Apple").status(BrandStatus.ACTIVE).build();
        when(brandRepository.findByStatusOrderByNameAsc(BrandStatus.ACTIVE))
                .thenReturn(List.of(activeBrand));

        Product product = Product.builder()
                .id(productId)
                .name("iPad Pro")
                .categoryId(categoryId)
                .brandId(brandId)
                .status(ProductStatus.ACTIVE)
                .build();
        when(productRepository.findByDeletedAtIsNullOrderByIdAsc(PageRequest.of(0, 50)))
                .thenReturn(List.of(product));
        when(productRepository.findByUpdatedAtGreaterThanEqual(any()))
                .thenReturn(List.of());

        Sku sku = Sku.builder()
                .id(UUID.randomUUID())
                .productId(productId)
                .skuCode("IPAD-PRO-11")
                .name("iPad Pro 11-inch")
                .price(new BigDecimal("22000000"))
                .currency("VND")
                .status(SkuStatus.ACTIVE)
                .build();
        when(skuRepository.findByProductIdAndStatusAndDeletedAtIsNull(productId, SkuStatus.ACTIVE))
                .thenReturn(List.of(sku));
        when(productImageRepository.findByProductIdOrderByDisplayOrderAscCreatedAtAsc(productId))
                .thenReturn(List.of());

        when(elasticsearchIndexService.getCurrentPhysicalIndex("product_catalog"))
                .thenReturn("product_catalog-v1");

        reindexService.executeReindex(jobId, targetIndex);

        // Verify documents saved to target physical index
        verify(elasticsearchOperations, atLeastOnce()).save(anyList(), eq(IndexCoordinates.of(targetIndex)));
        // Verify atomic swap called
        verify(elasticsearchIndexService).swapAlias("product_catalog", "product_catalog-v1", targetIndex);
        // Verify job marked completed
        assertThat(job.getStatus()).isEqualTo(ReindexJobStatus.COMPLETED);
        assertThat(job.getProcessedDocuments()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Negative: Failure during reindexing marks job FAILED, cleans up abandoned index, and does NOT swap alias")
    void testExecuteReindex_Failure_CleansUpAbandonedTargetAndDoesNotSwapAlias() {
        UUID jobId = UUID.randomUUID();
        String targetIndex = "product_catalog-v9999";

        CatalogReindexJob job = CatalogReindexJob.builder()
                .id(jobId)
                .status(ReindexJobStatus.QUEUED)
                .targetIndex(targetIndex)
                .totalDocuments(1L)
                .processedDocuments(0L)
                .failedDocuments(0L)
                .build();
        when(reindexJobRepository.findById(jobId)).thenReturn(Optional.of(job));

        UUID categoryId = UUID.randomUUID();
        UUID brandId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        Category activeCat = Category.builder().id(categoryId).name("Tech").status(CategoryStatus.ACTIVE).build();
        when(categoryRepository.findByStatusOrderByDisplayOrderAscNameAscIdAsc(CategoryStatus.ACTIVE))
                .thenReturn(List.of(activeCat));

        Brand activeBrand = Brand.builder().id(brandId).name("Apple").status(BrandStatus.ACTIVE).build();
        when(brandRepository.findByStatusOrderByNameAsc(BrandStatus.ACTIVE))
                .thenReturn(List.of(activeBrand));

        Product product = Product.builder()
                .id(productId)
                .name("iPad Pro")
                .categoryId(categoryId)
                .brandId(brandId)
                .status(ProductStatus.ACTIVE)
                .build();
        when(productRepository.findByDeletedAtIsNullOrderByIdAsc(PageRequest.of(0, 50)))
                .thenReturn(List.of(product));

        Sku sku = Sku.builder()
                .id(UUID.randomUUID())
                .productId(productId)
                .skuCode("IPAD-PRO-11")
                .name("iPad Pro 11-inch")
                .price(new BigDecimal("22000000"))
                .currency("VND")
                .status(SkuStatus.ACTIVE)
                .build();
        when(skuRepository.findByProductIdAndStatusAndDeletedAtIsNull(productId, SkuStatus.ACTIVE))
                .thenReturn(List.of(sku));

        // Simulate Elasticsearch bulk write failure
        when(elasticsearchOperations.save(anyList(), eq(IndexCoordinates.of(targetIndex))))
                .thenThrow(new RuntimeException("Elasticsearch cluster disk full"));

        reindexService.executeReindex(jobId, targetIndex);

        // Verify swapAlias was NEVER called
        verify(elasticsearchIndexService, never()).swapAlias(anyString(), any(), anyString());
        // Verify abandoned target index was deleted/cleaned up
        verify(elasticsearchIndexService).deletePhysicalIndex(targetIndex);
        // Verify job marked FAILED with sanitized error
        assertThat(job.getStatus()).isEqualTo(ReindexJobStatus.FAILED);
        assertThat(job.getErrorSummary()).contains("disk full");
    }

    @Test
    @DisplayName("Positive: Catch-up sync represents changes during reindex before alias swap")
    void testExecuteReindex_ChangesDuringReindex_RepresentedInTargetIndex() {
        UUID jobId = UUID.randomUUID();
        String targetIndex = "product_catalog-v8888";

        CatalogReindexJob job = CatalogReindexJob.builder()
                .id(jobId)
                .status(ReindexJobStatus.QUEUED)
                .targetIndex(targetIndex)
                .totalDocuments(1L)
                .processedDocuments(0L)
                .failedDocuments(0L)
                .startedAt(Instant.now().minusSeconds(10))
                .build();
        when(reindexJobRepository.findById(jobId)).thenReturn(Optional.of(job));

        UUID categoryId = UUID.randomUUID();
        UUID brandId = UUID.randomUUID();
        UUID productId1 = UUID.randomUUID();
        UUID productId2 = UUID.randomUUID(); // modified during reindex

        Category activeCat = Category.builder().id(categoryId).name("Tech").status(CategoryStatus.ACTIVE).build();
        when(categoryRepository.findByStatusOrderByDisplayOrderAscNameAscIdAsc(CategoryStatus.ACTIVE))
                .thenReturn(List.of(activeCat));

        Brand activeBrand = Brand.builder().id(brandId).name("Apple").status(BrandStatus.ACTIVE).build();
        when(brandRepository.findByStatusOrderByNameAsc(BrandStatus.ACTIVE))
                .thenReturn(List.of(activeBrand));

        Product product1 = Product.builder().id(productId1).name("P1").categoryId(categoryId).brandId(brandId).status(ProductStatus.ACTIVE).build();
        Product product2 = Product.builder().id(productId2).name("P2 Concurrent").categoryId(categoryId).brandId(brandId).status(ProductStatus.ACTIVE).build();

        // Keyset returns product1
        when(productRepository.findByDeletedAtIsNullOrderByIdAsc(PageRequest.of(0, 50)))
                .thenReturn(List.of(product1));

        // Catch-up query returns product2 which was concurrently modified
        when(productRepository.findByUpdatedAtGreaterThanEqual(any()))
                .thenReturn(List.of(product2));
        when(productRepository.findById(productId2))
                .thenReturn(Optional.of(product2));

        Sku sku1 = Sku.builder().id(UUID.randomUUID()).productId(productId1).skuCode("SKU-1").name("S1").price(new BigDecimal("1000")).currency("VND").status(SkuStatus.ACTIVE).build();
        Sku sku2 = Sku.builder().id(UUID.randomUUID()).productId(productId2).skuCode("SKU-2").name("S2").price(new BigDecimal("2000")).currency("VND").status(SkuStatus.ACTIVE).build();

        when(skuRepository.findByProductIdAndStatusAndDeletedAtIsNull(productId1, SkuStatus.ACTIVE)).thenReturn(List.of(sku1));
        when(skuRepository.findByProductIdAndStatusAndDeletedAtIsNull(productId2, SkuStatus.ACTIVE)).thenReturn(List.of(sku2));

        when(elasticsearchIndexService.getCurrentPhysicalIndex("product_catalog")).thenReturn("product_catalog-v1");

        reindexService.executeReindex(jobId, targetIndex);

        // Verify save was called for initial batch and catch-up batch
        verify(elasticsearchOperations, times(2)).save(anyList(), eq(IndexCoordinates.of(targetIndex)));
        // Verify atomic swap succeeded
        verify(elasticsearchIndexService).swapAlias("product_catalog", "product_catalog-v1", targetIndex);
        assertThat(job.getStatus()).isEqualTo(ReindexJobStatus.COMPLETED);
    }


    @Test
    @DisplayName("Positive: getJobStatus returns job details")
    void testGetJobStatus_Success() {
        UUID jobId = UUID.randomUUID();
        CatalogReindexJob job = CatalogReindexJob.builder()
                .id(jobId)
                .status(ReindexJobStatus.COMPLETED)
                .targetIndex("product_catalog-v1")
                .totalDocuments(100L)
                .processedDocuments(100L)
                .failedDocuments(0L)
                .createdAt(Instant.now())
                .build();
        when(reindexJobRepository.findById(jobId)).thenReturn(Optional.of(job));

        ReindexJobResponse resp = reindexService.getJobStatus(jobId);
        assertThat(resp).isNotNull();
        assertThat(resp.getId()).isEqualTo(jobId);
        assertThat(resp.getStatus()).isEqualTo(ReindexJobStatus.COMPLETED);
    }

    @Test
    @DisplayName("Negative: getJobStatus with unknown jobId throws ResourceNotFoundException")
    void testGetJobStatus_NotFound() {
        UUID jobId = UUID.randomUUID();
        when(reindexJobRepository.findById(jobId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reindexService.getJobStatus(jobId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("Regression: executeReindex preserves existing stock and inventoryVersion from previous Elasticsearch documents")
    @SuppressWarnings("unchecked")
    void testExecuteReindex_PreservesExistingStockState() {
        UUID jobId = UUID.randomUUID();
        String targetIndex = "product_catalog-v9999";

        CatalogReindexJob job = CatalogReindexJob.builder()
                .id(jobId)
                .status(ReindexJobStatus.QUEUED)
                .targetIndex(targetIndex)
                .totalDocuments(1L)
                .processedDocuments(0L)
                .failedDocuments(0L)
                .startedAt(Instant.now().minusSeconds(10))
                .build();
        when(reindexJobRepository.findById(jobId)).thenReturn(Optional.of(job));

        UUID categoryId = UUID.randomUUID();
        UUID brandId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();

        Category activeCat = Category.builder().id(categoryId).name("Tech").status(CategoryStatus.ACTIVE).build();
        when(categoryRepository.findByStatusOrderByDisplayOrderAscNameAscIdAsc(CategoryStatus.ACTIVE))
                .thenReturn(List.of(activeCat));

        Brand activeBrand = Brand.builder().id(brandId).name("Apple").status(BrandStatus.ACTIVE).build();
        when(brandRepository.findByStatusOrderByNameAsc(BrandStatus.ACTIVE))
                .thenReturn(List.of(activeBrand));

        Product product = Product.builder().id(productId).name("iPhone 16").categoryId(categoryId).brandId(brandId).status(ProductStatus.ACTIVE).build();
        when(productRepository.findByDeletedAtIsNullOrderByIdAsc(PageRequest.of(0, 50)))
                .thenReturn(List.of(product));
        when(productRepository.findByUpdatedAtGreaterThanEqual(any())).thenReturn(List.of());

        Sku sku = Sku.builder().id(skuId).productId(productId).skuCode("IP16-BLK").name("iPhone 16 Black").price(new BigDecimal("25000000")).currency("VND").status(SkuStatus.ACTIVE).build();
        when(skuRepository.findByProductIdAndStatusAndDeletedAtIsNull(productId, SkuStatus.ACTIVE)).thenReturn(List.of(sku));
        when(productImageRepository.findByProductIdOrderByDisplayOrderAscCreatedAtAsc(productId)).thenReturn(List.of());

        // Existing document has learned stock state from inventory consumer
        ProductSkuDocument existingDoc = ProductSkuDocument.builder()
                .skuId(skuId.toString())
                .inStock(true)
                .inventoryVersion(42L)
                .build();
        when(productSkuRepository.findById(skuId.toString())).thenReturn(Optional.of(existingDoc));

        when(elasticsearchIndexService.getCurrentPhysicalIndex("product_catalog")).thenReturn("product_catalog-v1");

        reindexService.executeReindex(jobId, targetIndex);

        org.mockito.ArgumentCaptor<List<ProductSkuDocument>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(elasticsearchOperations).save(captor.capture(), eq(IndexCoordinates.of(targetIndex)));

        List<ProductSkuDocument> savedDocs = captor.getValue();
        assertThat(savedDocs).hasSize(1);
        assertThat(savedDocs.get(0).getInStock()).isTrue();
        assertThat(savedDocs.get(0).getInventoryVersion()).isEqualTo(42L);
        assertThat(job.getStatus()).isEqualTo(ReindexJobStatus.COMPLETED);
    }

    @Test
    @DisplayName("Regression: Catch-up sync scans SKU updates, outbox events, and modified categories/brands")
    @SuppressWarnings("unchecked")
    void testExecuteReindex_CatchesSkuChangesAndOutboxEventsDuringReindex() {
        UUID jobId = UUID.randomUUID();
        String targetIndex = "product_catalog-v9999";

        CatalogReindexJob job = CatalogReindexJob.builder()
                .id(jobId)
                .status(ReindexJobStatus.QUEUED)
                .targetIndex(targetIndex)
                .totalDocuments(1L)
                .processedDocuments(0L)
                .failedDocuments(0L)
                .startedAt(Instant.now().minusSeconds(10))
                .build();
        when(reindexJobRepository.findById(jobId)).thenReturn(Optional.of(job));

        UUID categoryId = UUID.randomUUID();
        UUID brandId = UUID.randomUUID();
        UUID pInitial = UUID.randomUUID();
        UUID pSkuUpdated = UUID.randomUUID();
        UUID pOutbox = UUID.randomUUID();

        Category activeCat = Category.builder().id(categoryId).name("Tech").status(CategoryStatus.ACTIVE).build();
        when(categoryRepository.findByStatusOrderByDisplayOrderAscNameAscIdAsc(CategoryStatus.ACTIVE))
                .thenReturn(List.of(activeCat));

        Brand activeBrand = Brand.builder().id(brandId).name("Apple").status(BrandStatus.ACTIVE).build();
        when(brandRepository.findByStatusOrderByNameAsc(BrandStatus.ACTIVE))
                .thenReturn(List.of(activeBrand));

        Product productInit = Product.builder().id(pInitial).name("P Initial").categoryId(categoryId).brandId(brandId).status(ProductStatus.ACTIVE).build();
        Product productSkuMod = Product.builder().id(pSkuUpdated).name("P SKU Modified").categoryId(categoryId).brandId(brandId).status(ProductStatus.ACTIVE).build();
        Product productOutboxMod = Product.builder().id(pOutbox).name("P Outbox Modified").categoryId(categoryId).brandId(brandId).status(ProductStatus.ACTIVE).build();

        when(productRepository.findByDeletedAtIsNullOrderByIdAsc(PageRequest.of(0, 50)))
                .thenReturn(List.of(productInit));

        // Catch-up query stubs:
        when(productRepository.findByUpdatedAtGreaterThanEqual(any())).thenReturn(List.of());
        when(skuRepository.findProductIdsBySkuUpdatedAtGreaterThanEqual(any())).thenReturn(List.of(pSkuUpdated));
        when(outboxEventRepository.findByCreatedAtGreaterThanEqual(any())).thenReturn(List.of(
                OutboxEvent.builder().aggregateType("PRODUCT").aggregateId(pOutbox.toString()).build()
        ));
        when(categoryRepository.findByUpdatedAtGreaterThanEqual(any())).thenReturn(List.of());
        when(brandRepository.findByUpdatedAtGreaterThanEqual(any())).thenReturn(List.of());

        when(productRepository.findById(pSkuUpdated)).thenReturn(Optional.of(productSkuMod));
        when(productRepository.findById(pOutbox)).thenReturn(Optional.of(productOutboxMod));

        Sku skuInit = Sku.builder().id(UUID.randomUUID()).productId(pInitial).skuCode("SKU-INIT").name("Init").price(new BigDecimal("1000")).currency("VND").status(SkuStatus.ACTIVE).build();
        Sku skuMod = Sku.builder().id(UUID.randomUUID()).productId(pSkuUpdated).skuCode("SKU-MOD").name("Mod").price(new BigDecimal("2000")).currency("VND").status(SkuStatus.ACTIVE).build();
        Sku skuOutbox = Sku.builder().id(UUID.randomUUID()).productId(pOutbox).skuCode("SKU-OUTBOX").name("Outbox").price(new BigDecimal("3000")).currency("VND").status(SkuStatus.ACTIVE).build();

        when(skuRepository.findByProductIdAndStatusAndDeletedAtIsNull(pInitial, SkuStatus.ACTIVE)).thenReturn(List.of(skuInit));
        when(skuRepository.findByProductIdAndStatusAndDeletedAtIsNull(pSkuUpdated, SkuStatus.ACTIVE)).thenReturn(List.of(skuMod));
        when(skuRepository.findByProductIdAndStatusAndDeletedAtIsNull(pOutbox, SkuStatus.ACTIVE)).thenReturn(List.of(skuOutbox));

        when(elasticsearchIndexService.getCurrentPhysicalIndex("product_catalog")).thenReturn("product_catalog-v1");

        reindexService.executeReindex(jobId, targetIndex);

        // Initial batch saved + catch-up batch saved
        verify(elasticsearchOperations, times(2)).save(anyList(), eq(IndexCoordinates.of(targetIndex)));
        verify(elasticsearchIndexService).swapAlias("product_catalog", "product_catalog-v1", targetIndex);
        assertThat(job.getStatus()).isEqualTo(ReindexJobStatus.COMPLETED);
    }

    @Test
    @DisplayName("Negative: Target write failure marks job FAILED, cleans up target index, and does not swap alias")
    void testExecuteReindex_TargetWriteFailure_FailsJobAndDoesNotSwapAlias() {
        UUID jobId = UUID.randomUUID();
        String targetIndex = "product_catalog-v9999";

        CatalogReindexJob job = CatalogReindexJob.builder()
                .id(jobId)
                .status(ReindexJobStatus.QUEUED)
                .targetIndex(targetIndex)
                .totalDocuments(1L)
                .processedDocuments(0L)
                .failedDocuments(0L)
                .startedAt(Instant.now().minusSeconds(10))
                .build();
        when(reindexJobRepository.findById(jobId)).thenReturn(Optional.of(job));

        UUID categoryId = UUID.randomUUID();
        UUID brandId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        Category activeCat = Category.builder().id(categoryId).name("Tech").status(CategoryStatus.ACTIVE).build();
        when(categoryRepository.findByStatusOrderByDisplayOrderAscNameAscIdAsc(CategoryStatus.ACTIVE))
                .thenReturn(List.of(activeCat));

        Brand activeBrand = Brand.builder().id(brandId).name("Apple").status(BrandStatus.ACTIVE).build();
        when(brandRepository.findByStatusOrderByNameAsc(BrandStatus.ACTIVE))
                .thenReturn(List.of(activeBrand));

        Product product = Product.builder().id(productId).name("iPad").categoryId(categoryId).brandId(brandId).status(ProductStatus.ACTIVE).build();
        when(productRepository.findByDeletedAtIsNullOrderByIdAsc(PageRequest.of(0, 50)))
                .thenReturn(List.of(product));

        Sku sku = Sku.builder().id(UUID.randomUUID()).productId(productId).skuCode("IPAD-1").name("iPad").price(new BigDecimal("15000000")).currency("VND").status(SkuStatus.ACTIVE).build();
        when(skuRepository.findByProductIdAndStatusAndDeletedAtIsNull(productId, SkuStatus.ACTIVE)).thenReturn(List.of(sku));

        // Simulate bulk write failure to Elasticsearch
        when(elasticsearchOperations.save(anyList(), eq(IndexCoordinates.of(targetIndex))))
                .thenThrow(new RuntimeException("Elasticsearch connection reset"));

        reindexService.executeReindex(jobId, targetIndex);

        // Job must be failed
        assertThat(job.getStatus()).isEqualTo(ReindexJobStatus.FAILED);
        assertThat(job.getErrorSummary()).contains("Elasticsearch connection reset");
        // Target index must be deleted
        verify(elasticsearchIndexService).deletePhysicalIndex(targetIndex);
        // Alias must NOT be swapped
        verify(elasticsearchIndexService, never()).swapAlias(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Negative: Reindex job marked FAILED before swap aborts alias swap")
    void testExecuteReindex_ConcurrentJobFailure_AbortsAliasSwap() {
        UUID jobId = UUID.randomUUID();
        String targetIndex = "product_catalog-v9999";

        CatalogReindexJob job = CatalogReindexJob.builder()
                .id(jobId)
                .status(ReindexJobStatus.QUEUED)
                .targetIndex(targetIndex)
                .totalDocuments(1L)
                .processedDocuments(0L)
                .failedDocuments(0L)
                .startedAt(Instant.now().minusSeconds(10))
                .build();

        CatalogReindexJob failedJob = CatalogReindexJob.builder()
                .id(jobId)
                .status(ReindexJobStatus.FAILED)
                .errorSummary("Concurrent dual-write error")
                .targetIndex(targetIndex)
                .build();

        // First find returns normal job, second find before swap returns FAILED job
        when(reindexJobRepository.findById(jobId))
                .thenReturn(Optional.of(job))
                .thenReturn(Optional.of(failedJob));

        when(categoryRepository.findByStatusOrderByDisplayOrderAscNameAscIdAsc(CategoryStatus.ACTIVE))
                .thenReturn(List.of());
        when(brandRepository.findByStatusOrderByNameAsc(BrandStatus.ACTIVE))
                .thenReturn(List.of());
        when(productRepository.findByDeletedAtIsNullOrderByIdAsc(any()))
                .thenReturn(List.of());
        when(productRepository.findByUpdatedAtGreaterThanEqual(any()))
                .thenReturn(List.of());

        reindexService.executeReindex(jobId, targetIndex);

        verify(elasticsearchIndexService, never()).swapAlias(anyString(), anyString(), anyString());
        verify(elasticsearchIndexService).deletePhysicalIndex(targetIndex);
    }

    @Test
    @DisplayName("Positive: Reindex preserves stock from SkuStockSnapshot when Elasticsearch document is absent or unknown")
    @SuppressWarnings("unchecked")
    void testReindex_StockSnapshotPreserved_WhenElasticsearchDocAbsentOrUnknown() {
        UUID jobId = UUID.randomUUID();
        String targetIndex = "product_catalog-v9999";

        CatalogReindexJob job = CatalogReindexJob.builder()
                .id(jobId)
                .status(ReindexJobStatus.QUEUED)
                .targetIndex(targetIndex)
                .totalDocuments(1L)
                .processedDocuments(0L)
                .failedDocuments(0L)
                .startedAt(Instant.now().minusSeconds(10))
                .build();
        when(reindexJobRepository.findById(jobId)).thenReturn(Optional.of(job));

        UUID categoryId = UUID.randomUUID();
        UUID brandId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();

        Category activeCat = Category.builder().id(categoryId).name("Tech").status(CategoryStatus.ACTIVE).build();
        when(categoryRepository.findByStatusOrderByDisplayOrderAscNameAscIdAsc(CategoryStatus.ACTIVE))
                .thenReturn(List.of(activeCat));

        Brand activeBrand = Brand.builder().id(brandId).name("Apple").status(BrandStatus.ACTIVE).build();
        when(brandRepository.findByStatusOrderByNameAsc(BrandStatus.ACTIVE))
                .thenReturn(List.of(activeBrand));

        Product product = Product.builder().id(productId).name("iPhone 15").categoryId(categoryId).brandId(brandId).status(ProductStatus.ACTIVE).build();
        when(productRepository.findByDeletedAtIsNullOrderByIdAsc(PageRequest.of(0, 50)))
                .thenReturn(List.of(product));
        when(productRepository.findByUpdatedAtGreaterThanEqual(any()))
                .thenReturn(List.of());

        Sku sku = Sku.builder().id(skuId).productId(productId).skuCode("IP15-128").name("128GB").price(new BigDecimal("20000000")).currency("VND").status(SkuStatus.ACTIVE).build();
        when(skuRepository.findByProductIdAndStatusAndDeletedAtIsNull(productId, SkuStatus.ACTIVE))
                .thenReturn(List.of(sku));

        // ES doc is UNKNOWN (inStock = null, version = null)
        ProductSkuDocument esDoc = ProductSkuDocument.builder().skuId(skuId.toString()).inStock(null).inventoryVersion(null).build();
        when(productSkuRepository.findById(skuId.toString())).thenReturn(Optional.of(esDoc));

        // Durable snapshot has known stock from out-of-order event (inStock = true, version = 5)
        SkuStockSnapshot snapshot = SkuStockSnapshot.builder()
                .skuId(skuId)
                .inStock(true)
                .inventoryVersion(5L)
                .updatedAt(Instant.now())
                .build();
        when(skuStockSnapshotRepository.findById(skuId)).thenReturn(Optional.of(snapshot));

        when(elasticsearchIndexService.getCurrentPhysicalIndex("product_catalog")).thenReturn("product_catalog-v1");

        reindexService.executeReindex(jobId, targetIndex);

        org.mockito.ArgumentCaptor<List<ProductSkuDocument>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(elasticsearchOperations, atLeastOnce()).save(captor.capture(), eq(IndexCoordinates.of(targetIndex)));

        List<ProductSkuDocument> savedDocs = captor.getValue();
        assertThat(savedDocs).hasSize(1);
        assertThat(savedDocs.get(0).getInStock()).isTrue();
        assertThat(savedDocs.get(0).getInventoryVersion()).isEqualTo(5L);
        assertThat(job.getStatus()).isEqualTo(ReindexJobStatus.COMPLETED);
    }

    @Test
    @DisplayName("Positive: Worker cannot run before transaction commit and runs after commit")
    void testCreateReindexJob_ExecutesWorkerOnlyAfterTransactionCommit() {
        UUID jobId = UUID.randomUUID();
        when(skuRepository.countByStatusAndDeletedAtIsNull(SkuStatus.ACTIVE)).thenReturn(10L);
        when(reindexJobRepository.save(any(CatalogReindexJob.class))).thenAnswer(invocation -> {
            CatalogReindexJob job = invocation.getArgument(0);
            job.setId(jobId);
            return job;
        });

        // Initialize active transaction synchronization
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        try {
            ReindexJobResponse resp = reindexService.createReindexJob("admin-1");
            assertThat(resp).isNotNull();
            assertThat(resp.getId()).isEqualTo(jobId);

            // Verify before commit: executeReindex has NOT queried the database or run
            verify(reindexJobRepository, never()).findById(jobId);

            // Trigger afterCommit on all registered synchronizations
            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).isNotEmpty();
            for (TransactionSynchronization sync : synchronizations) {
                sync.afterCommit();
            }

            // After commit: worker runs
            verify(reindexJobRepository, timeout(3000).atLeastOnce()).findById(jobId);

        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("Positive: Failure while persisting completion after alias swap never deletes target index")
    void testExecuteReindex_FailurePersistingCompletion_PreservesTargetIndex() {
        UUID jobId = UUID.randomUUID();
        String targetIndex = "product_catalog-v7777";

        CatalogReindexJob job = CatalogReindexJob.builder()
                .id(jobId)
                .status(ReindexJobStatus.QUEUED)
                .targetIndex(targetIndex)
                .totalDocuments(0L)
                .processedDocuments(0L)
                .failedDocuments(0L)
                .startedAt(Instant.now().minusSeconds(5))
                .build();

        when(reindexJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(categoryRepository.findByStatusOrderByDisplayOrderAscNameAscIdAsc(CategoryStatus.ACTIVE)).thenReturn(List.of());
        when(brandRepository.findByStatusOrderByNameAsc(BrandStatus.ACTIVE)).thenReturn(List.of());
        when(productRepository.findByDeletedAtIsNullOrderByIdAsc(any())).thenReturn(List.of());
        when(productRepository.findByUpdatedAtGreaterThanEqual(any())).thenReturn(List.of());
        when(elasticsearchIndexService.getCurrentPhysicalIndex("product_catalog")).thenReturn("product_catalog-old");

        // Simulate DB failure when saving COMPLETED status after alias swap
        doAnswer(invocation -> invocation.getArgument(0))
                .doThrow(new RuntimeException("DB connection error on completion save"))
                .when(reindexJobRepository).save(any(CatalogReindexJob.class));

        reindexService.executeReindex(jobId, targetIndex);

        // Verify alias swap occurred
        verify(elasticsearchIndexService).swapAlias("product_catalog", "product_catalog-old", targetIndex);

        // CRITICAL INVARIANT: target index was NEVER deleted despite the post-swap DB failure!
        verify(elasticsearchIndexService, never()).deletePhysicalIndex(targetIndex);
    }
}
