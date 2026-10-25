package com.example.catalogservice.elasticsearch.service;

import com.ecommerce.common.exception.ResourceNotFoundException;
import com.example.catalogservice.dto.resp.ReindexJobResponse;
import com.example.catalogservice.elasticsearch.document.ImageDoc;
import com.example.catalogservice.elasticsearch.document.ProductSkuDocument;
import com.ecommerce.common.outbox.OutboxEvent;
import com.ecommerce.common.outbox.OutboxEventRepository;
import com.example.catalogservice.elasticsearch.repository.ProductSkuRepository;
import com.example.catalogservice.entity.*;
import com.example.catalogservice.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogReindexService {

    private final CatalogReindexJobRepository reindexJobRepository;
    private final ElasticsearchIndexService elasticsearchIndexService;
    private final ElasticsearchOperations elasticsearchOperations;
    private final ProductRepository productRepository;
    private final SkuRepository skuRepository;
    private final CategoryRepository categoryRepository;
    private final BrandRepository brandRepository;
    private final ProductImageRepository productImageRepository;
    private final ProductSkuRepository productSkuRepository;
    private final SkuStockSnapshotRepository skuStockSnapshotRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Value("${catalog.index.name:product_catalog}")
    private String defaultAliasName;

    @Transactional
    public ReindexJobResponse createReindexJob(String actorId) {
        String targetIndex = defaultAliasName + "-v" + System.currentTimeMillis();

        // 1. Create physical index with mappings
        elasticsearchIndexService.createPhysicalIndex(targetIndex);

        // 2. Count active sellable SKU docs for total
        long totalSkuDocs = skuRepository.countByStatusAndDeletedAtIsNull(SkuStatus.ACTIVE);

        // 3. Create job record in database
        CatalogReindexJob job = CatalogReindexJob.builder()
                .status(ReindexJobStatus.QUEUED)
                .targetIndex(targetIndex)
                .totalDocuments(totalSkuDocs)
                .processedDocuments(0L)
                .failedDocuments(0L)
                .startedAt(Instant.now())
                .build();

        CatalogReindexJob savedJob = reindexJobRepository.save(job);
        UUID jobId = savedJob.getId();

        // 4. Asynchronously execute reindexing only after the creating transaction commits
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    CompletableFuture.runAsync(() -> executeReindex(jobId, targetIndex));
                }
            });
        } else {
            CompletableFuture.runAsync(() -> executeReindex(jobId, targetIndex));
        }

        return toResponse(savedJob);
    }

    public void executeReindex(UUID jobId, String targetIndex) {
        CatalogReindexJob job = reindexJobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.error("Reindex job {} not found for execution", jobId);
            return;
        }

        if (job.getStartedAt() == null) {
            job.setStartedAt(Instant.now());
        }
        job.setStatus(ReindexJobStatus.RUNNING);
        reindexJobRepository.save(job);
        log.info("Starting reindexing for job {} into physical index {}", jobId, targetIndex);

        boolean aliasSwapped = false;
        try {
            // Pre-load active categories and brands lookup
            Map<UUID, Category> activeCategories = categoryRepository
                    .findByStatusOrderByDisplayOrderAscNameAscIdAsc(CategoryStatus.ACTIVE)
                    .stream()
                    .collect(Collectors.toMap(Category::getId, c -> c));

            Map<UUID, Brand> activeBrands = brandRepository
                    .findByStatusOrderByNameAsc(BrandStatus.ACTIVE)
                    .stream()
                    .collect(Collectors.toMap(Brand::getId, b -> b));

            int pageSize = 50;
            UUID lastId = null;
            long processedCount = 0;
            boolean hasMore = true;

            while (hasMore) {
                List<Product> products = (lastId == null)
                        ? productRepository.findByDeletedAtIsNullOrderByIdAsc(PageRequest.of(0, pageSize))
                        : productRepository.findByIdGreaterThanAndDeletedAtIsNullOrderByIdAsc(lastId, PageRequest.of(0, pageSize));

                if (products.isEmpty()) {
                    hasMore = false;
                    break;
                }

                lastId = products.get(products.size() - 1).getId();

                List<ProductSkuDocument> documentsToSave = new ArrayList<>();

                for (Product product : products) {
                    if (product.getDeletedAt() != null || product.getStatus() != ProductStatus.ACTIVE) {
                        continue;
                    }

                    Category category = activeCategories.get(product.getCategoryId());
                    Brand brand = activeBrands.get(product.getBrandId());

                    if (category == null || brand == null) {
                        continue;
                    }

                    List<Sku> activeSkus = skuRepository.findByProductIdAndStatusAndDeletedAtIsNull(product.getId(), SkuStatus.ACTIVE);
                    if (activeSkus.isEmpty()) {
                        continue;
                    }

                    List<ProductImage> images = productImageRepository.findByProductIdOrderByDisplayOrderAscCreatedAtAsc(product.getId());
                    List<ImageDoc> imageDocs = images.stream().map(img -> ImageDoc.builder()
                            .url(img.getUrl())
                            .altText(img.getAltText())
                            .primary(img.isPrimary())
                            .displayOrder(img.getDisplayOrder())
                            .build()).collect(Collectors.toList());

                    for (Sku sku : activeSkus) {
                        Map<String, Object> attributesMap = parseAttributes(sku.getAttributes());

                        ProductSkuDocument existingDoc = productSkuRepository != null
                                ? productSkuRepository.findById(sku.getId().toString()).orElse(null)
                                : null;
                        SkuStockSnapshot snapshot = skuStockSnapshotRepository != null
                                ? skuStockSnapshotRepository.findById(sku.getId()).orElse(null)
                                : null;
                        StockResolutionUtil.ResolvedStock resolvedStock = StockResolutionUtil.resolve(existingDoc, snapshot);
                        Boolean inStock = resolvedStock.inStock();
                        Long inventoryVersion = resolvedStock.inventoryVersion();

                        ProductSkuDocument doc = ProductSkuDocument.builder()
                                .skuId(sku.getId().toString())
                                .skuCode(sku.getSkuCode())
                                .productId(product.getId().toString())
                                .productName(product.getName())
                                .name(sku.getName())
                                .description(product.getDescription())
                                .categoryId(category.getId().toString())
                                .categoryName(category.getName())
                                .brandId(brand.getId().toString())
                                .brandName(brand.getName())
                                .price(sku.getPrice().doubleValue())
                                .currency(sku.getCurrency())
                                .status(sku.getStatus().name())
                                .attributes(attributesMap)
                                .images(imageDocs)
                                .inStock(inStock)
                                .inventoryVersion(inventoryVersion)
                                .aggregateRevision(product.getUpdatedAt() != null ? product.getUpdatedAt().toEpochMilli() : 0L)
                                .updatedAt(sku.getUpdatedAt() != null ? sku.getUpdatedAt() : Instant.now())
                                .build();

                        documentsToSave.add(doc);
                    }
                }

                if (!documentsToSave.isEmpty()) {
                    elasticsearchOperations.save(documentsToSave, IndexCoordinates.of(targetIndex));
                    processedCount += documentsToSave.size();
                }

                job.setProcessedDocuments(processedCount);
                reindexJobRepository.save(job);

                if (products.size() < pageSize) {
                    hasMore = false;
                }
            }

            // 4b. Catch-up sync changes that occurred since job started
            syncCatchUpChanges(job.getStartedAt(), targetIndex, activeCategories, activeBrands);

            // Verify job wasn't marked FAILED by concurrent dual-write errors
            CatalogReindexJob currentJob = reindexJobRepository.findById(jobId).orElse(null);
            if (currentJob != null && currentJob.getStatus() == ReindexJobStatus.FAILED) {
                throw new IllegalStateException("Reindex job was marked FAILED during execution: " + currentJob.getErrorSummary());
            }

            // 5. Atomic alias swap only on complete success
            String oldPhysicalIndex = elasticsearchIndexService.getCurrentPhysicalIndex(defaultAliasName);
            elasticsearchIndexService.swapAlias(defaultAliasName, oldPhysicalIndex, targetIndex);
            aliasSwapped = true;

            try {
                job.setStatus(ReindexJobStatus.COMPLETED);
                job.setFinishedAt(Instant.now());
                reindexJobRepository.save(job);
                log.info("Reindex job {} successfully completed with {} documents. Alias {} swapped.",
                        jobId, processedCount, defaultAliasName);
            } catch (Exception postSwapEx) {
                log.error("Alias swap succeeded for job {}, but saving COMPLETED status to DB failed: {}. Target index {} remains active live alias.",
                        jobId, postSwapEx.getMessage(), targetIndex, postSwapEx);
                try {
                    job.setErrorSummary("Alias swapped successfully, but completion record save had error: " + sanitizeError(postSwapEx.getMessage()));
                    reindexJobRepository.save(job);
                } catch (Exception ignored) {
                    // ignore secondary failure
                }
            }

        } catch (Exception e) {
            log.error("Reindex job {} failed: {}. Existing alias [{}] remained untouched.",
                    jobId, e.getMessage(), defaultAliasName, e);

            // Clean up abandoned target index ONLY if alias swap has NOT occurred
            if (!aliasSwapped) {
                try {
                    elasticsearchIndexService.deletePhysicalIndex(targetIndex);
                } catch (Exception cleanupEx) {
                    log.warn("Failed to clean up abandoned target index {}: {}", targetIndex, cleanupEx.getMessage());
                }

                try {
                    job.setStatus(ReindexJobStatus.FAILED);
                    job.setErrorSummary(sanitizeError(e.getMessage()));
                    job.setFinishedAt(Instant.now());
                    reindexJobRepository.save(job);
                } catch (Exception dbEx) {
                    log.error("Could not record failure status for reindex job {}: {}", jobId, dbEx.getMessage());
                }
            }
        }
    }

    private void syncCatchUpChanges(Instant startedAt, String targetIndex,
                                     Map<UUID, Category> activeCategories, Map<UUID, Brand> activeBrands) {
        if (startedAt == null) return;

        // Collect all affected product IDs from products, SKUs, outbox, categories, and brands
        Set<UUID> affectedProductIds = new LinkedHashSet<>();

        List<Product> modifiedProducts = productRepository.findByUpdatedAtGreaterThanEqual(startedAt);
        modifiedProducts.forEach(p -> affectedProductIds.add(p.getId()));

        if (skuRepository != null) {
            List<UUID> productIdsWithSkuUpdates = skuRepository.findProductIdsBySkuUpdatedAtGreaterThanEqual(startedAt);
            affectedProductIds.addAll(productIdsWithSkuUpdates);
        }

        if (outboxEventRepository != null) {
            List<OutboxEvent> recentEvents = outboxEventRepository.findByCreatedAtGreaterThanEqual(startedAt);
            for (OutboxEvent event : recentEvents) {
                if ("PRODUCT".equalsIgnoreCase(event.getAggregateType()) && event.getAggregateId() != null) {
                    try {
                        affectedProductIds.add(UUID.fromString(event.getAggregateId()));
                    } catch (Exception ignored) {}
                }
            }
        }

        if (categoryRepository != null) {
            List<Category> modifiedCats = categoryRepository.findByUpdatedAtGreaterThanEqual(startedAt);
            for (Category c : modifiedCats) {
                List<Product> catProducts = productRepository.findByCategoryIdAndDeletedAtIsNullOrderByIdAsc(c.getId(), PageRequest.of(0, 1000));
                catProducts.forEach(p -> affectedProductIds.add(p.getId()));
            }
        }

        if (brandRepository != null) {
            List<Brand> modifiedBrands = brandRepository.findByUpdatedAtGreaterThanEqual(startedAt);
            for (Brand b : modifiedBrands) {
                List<Product> brandProducts = productRepository.findByBrandIdAndDeletedAtIsNullOrderByIdAsc(b.getId(), PageRequest.of(0, 1000));
                brandProducts.forEach(p -> affectedProductIds.add(p.getId()));
            }
        }

        log.info("Catch-up sync found {} affected product IDs since {}", affectedProductIds.size(), startedAt);

        List<ProductSkuDocument> docsToSave = new ArrayList<>();
        for (UUID productId : affectedProductIds) {
            Product product = productRepository.findById(productId).orElse(null);
            if (product == null || product.getDeletedAt() != null || product.getStatus() != ProductStatus.ACTIVE) {
                deleteProductFromTargetIndex(productId.toString(), targetIndex);
                continue;
            }

            Category category = activeCategories.get(product.getCategoryId());
            Brand brand = activeBrands.get(product.getBrandId());
            if (category == null || brand == null || category.getStatus() != CategoryStatus.ACTIVE || brand.getStatus() != BrandStatus.ACTIVE) {
                deleteProductFromTargetIndex(productId.toString(), targetIndex);
                continue;
            }

            List<Sku> activeSkus = skuRepository.findByProductIdAndStatusAndDeletedAtIsNull(product.getId(), SkuStatus.ACTIVE);
            List<ProductImage> images = productImageRepository.findByProductIdOrderByDisplayOrderAscCreatedAtAsc(product.getId());
            List<ImageDoc> imageDocs = images.stream().map(img -> ImageDoc.builder()
                    .url(img.getUrl())
                    .altText(img.getAltText())
                    .primary(img.isPrimary())
                    .displayOrder(img.getDisplayOrder())
                    .build()).collect(Collectors.toList());

            for (Sku sku : activeSkus) {
                ProductSkuDocument existingDoc = productSkuRepository != null
                        ? productSkuRepository.findById(sku.getId().toString()).orElse(null)
                        : null;
                SkuStockSnapshot snapshot = skuStockSnapshotRepository != null
                        ? skuStockSnapshotRepository.findById(sku.getId()).orElse(null)
                        : null;
                StockResolutionUtil.ResolvedStock resolvedStock = StockResolutionUtil.resolve(existingDoc, snapshot);
                Boolean inStock = resolvedStock.inStock();
                Long inventoryVersion = resolvedStock.inventoryVersion();

                docsToSave.add(ProductSkuDocument.builder()
                        .skuId(sku.getId().toString())
                        .skuCode(sku.getSkuCode())
                        .productId(product.getId().toString())
                        .productName(product.getName())
                        .name(sku.getName())
                        .description(product.getDescription())
                        .categoryId(category.getId().toString())
                        .categoryName(category.getName())
                        .brandId(brand.getId().toString())
                        .brandName(brand.getName())
                        .price(sku.getPrice().doubleValue())
                        .currency(sku.getCurrency())
                        .status(sku.getStatus().name())
                        .attributes(parseAttributes(sku.getAttributes()))
                        .images(imageDocs)
                        .inStock(inStock)
                        .inventoryVersion(inventoryVersion)
                        .aggregateRevision(product.getUpdatedAt() != null ? product.getUpdatedAt().toEpochMilli() : 0L)
                        .updatedAt(sku.getUpdatedAt() != null ? sku.getUpdatedAt() : Instant.now())
                        .build());
            }
        }
        if (!docsToSave.isEmpty()) {
            elasticsearchOperations.save(docsToSave, IndexCoordinates.of(targetIndex));
        }
    }

    private void deleteProductFromTargetIndex(String productId, String targetIndex) {
        co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder bq =
                new co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder();
        bq.filter(f -> f.term(t -> t.field("productId").value(productId)));
        org.springframework.data.elasticsearch.client.elc.NativeQuery query =
                org.springframework.data.elasticsearch.client.elc.NativeQuery.builder()
                        .withQuery(bq.build()._toQuery())
                        .build();
        org.springframework.data.elasticsearch.core.query.DeleteQuery deleteQuery =
                org.springframework.data.elasticsearch.core.query.DeleteQuery.builder(query).build();
        elasticsearchOperations.delete(deleteQuery, ProductSkuDocument.class, IndexCoordinates.of(targetIndex));
    }


    @Transactional(readOnly = true)
    public ReindexJobResponse getJobStatus(UUID jobId) {
        CatalogReindexJob job = reindexJobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Reindex job not found with id: " + jobId));
        return toResponse(job);
    }

    private Map<String, Object> parseAttributes(String attributesJson) {
        if (attributesJson == null || attributesJson.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(attributesJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private String sanitizeError(String error) {
        if (error == null) {
            return "Unknown error";
        }
        // Remove potential sensitive keys or tokens
        return error.replaceAll("(?i)(key|secret|token|password)=[a-zA-Z0-9_-]+", "$1=***");
    }

    private ReindexJobResponse toResponse(CatalogReindexJob job) {
        return ReindexJobResponse.builder()
                .id(job.getId())
                .status(job.getStatus())
                .targetIndex(job.getTargetIndex())
                .totalDocuments(job.getTotalDocuments())
                .processedDocuments(job.getProcessedDocuments())
                .failedDocuments(job.getFailedDocuments())
                .errorSummary(job.getErrorSummary())
                .startedAt(job.getStartedAt())
                .finishedAt(job.getFinishedAt())
                .createdAt(job.getCreatedAt())
                .build();
    }
}
