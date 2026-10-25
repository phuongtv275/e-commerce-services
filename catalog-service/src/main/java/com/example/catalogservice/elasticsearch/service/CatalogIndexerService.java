package com.example.catalogservice.elasticsearch.service;

import com.example.catalogservice.elasticsearch.document.ImageDoc;
import com.example.catalogservice.elasticsearch.document.ProductSkuDocument;
import com.example.catalogservice.elasticsearch.repository.ProductSkuRepository;
import com.example.catalogservice.entity.*;
import com.example.catalogservice.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogIndexerService {

    private final ProductSkuRepository productSkuRepository;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final BrandRepository brandRepository;
    private final SkuRepository skuRepository;
    private final ProductImageRepository productImageRepository;
    private final SkuStockSnapshotRepository skuStockSnapshotRepository;
    private final CatalogReindexJobRepository reindexJobRepository;
    private final ElasticsearchOperations elasticsearchOperations;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public void syncProductToElasticsearch(UUID productId, Long aggregateRevision) {
        Optional<Product> prodOpt = productRepository.findByIdAndDeletedAtIsNull(productId);

        if (prodOpt.isEmpty() || prodOpt.get().getStatus() != ProductStatus.ACTIVE) {
            log.info("Product {} is deleted or not ACTIVE. Removing documents from Elasticsearch.", productId);
            productSkuRepository.deleteByProductId(productId.toString());
            deleteProductFromRunningReindexTargets(productId.toString());
            return;
        }

        Product product = prodOpt.get();
        Category category = categoryRepository.findById(product.getCategoryId()).orElse(null);
        if (category == null || category.getStatus() != CategoryStatus.ACTIVE) {
            log.info("Category for product {} is not ACTIVE. Removing documents from Elasticsearch.", productId);
            productSkuRepository.deleteByProductId(productId.toString());
            deleteProductFromRunningReindexTargets(productId.toString());
            return;
        }

        Brand brand = brandRepository.findById(product.getBrandId()).orElse(null);
        if (brand == null || brand.getStatus() != BrandStatus.ACTIVE) {
            log.info("Brand for product {} is not ACTIVE. Removing documents from Elasticsearch.", productId);
            productSkuRepository.deleteByProductId(productId.toString());
            deleteProductFromRunningReindexTargets(productId.toString());
            return;
        }

        List<Sku> skus = skuRepository.findByProductIdAndDeletedAtIsNull(productId);
        List<ProductImage> images = productImageRepository.findByProductIdOrderByDisplayOrderAscCreatedAtAsc(productId);

        List<ImageDoc> imageDocs = images.stream().map(img -> ImageDoc.builder()
                .url(img.getUrl())
                .altText(img.getAltText())
                .primary(img.isPrimary())
                .displayOrder(img.getDisplayOrder())
                .build()).collect(Collectors.toList());

        for (Sku sku : skus) {
            String skuIdStr = sku.getId().toString();
            if (sku.getStatus() == SkuStatus.ACTIVE) {
                Optional<ProductSkuDocument> existingDocOpt = productSkuRepository.findById(skuIdStr);
                Optional<SkuStockSnapshot> snapshotOpt = skuStockSnapshotRepository.findById(sku.getId());

                if (existingDocOpt.isPresent()) {
                    ProductSkuDocument existing = existingDocOpt.get();
                    if (aggregateRevision != null && existing.getAggregateRevision() != null
                            && aggregateRevision < existing.getAggregateRevision()) {
                        log.debug("Skipping older revision {} for SKU {} (existing revision {})",
                                aggregateRevision, sku.getId(), existing.getAggregateRevision());
                        continue;
                    }
                }

                StockResolutionUtil.ResolvedStock resolvedStock = StockResolutionUtil.resolve(
                        existingDocOpt.orElse(null), snapshotOpt.orElse(null));
                Boolean inStock = resolvedStock.inStock();
                Long inventoryVersion = resolvedStock.inventoryVersion();

                Map<String, Object> attributesMap = parseAttributes(sku.getAttributes());

                ProductSkuDocument doc = ProductSkuDocument.builder()
                        .skuId(skuIdStr)
                        .productId(product.getId().toString())
                        .skuCode(sku.getSkuCode())
                        .categoryId(category.getId().toString())
                        .categoryName(category.getName())
                        .brandId(brand.getId().toString())
                        .brandName(brand.getName())
                        .name(sku.getName())
                        .productName(product.getName())
                        .description(product.getDescription())
                        .price(sku.getPrice().doubleValue())
                        .currency(sku.getCurrency())
                        .status(sku.getStatus().name())
                        .attributes(attributesMap)
                        .images(imageDocs)
                        .inStock(inStock)
                        .inventoryVersion(inventoryVersion)
                        .aggregateRevision(aggregateRevision)
                        .updatedAt(sku.getUpdatedAt() != null ? sku.getUpdatedAt() : Instant.now())
                        .build();

                productSkuRepository.save(doc);
                syncSkuToRunningReindexTargets(doc);
                log.debug("Indexed SKU document {} for product {}", sku.getId(), productId);
            } else {
                productSkuRepository.deleteById(skuIdStr);
                deleteSkuFromRunningReindexTargets(skuIdStr);
                log.debug("Removed inactive SKU document {} from Elasticsearch", sku.getId());
            }
        }
    }

    @Transactional
    public void syncStockToElasticsearch(UUID skuId, Long availableQuantity, Long version, Boolean inStock) {
        Boolean calcInStock = inStock != null ? inStock : (availableQuantity != null && availableQuantity > 0);
        long eventVersion = version != null ? version : 0L;

        // 1. Determine and update durable storage in PostgreSQL read model (SkuStockSnapshot)
        Optional<SkuStockSnapshot> snapshotOpt = skuStockSnapshotRepository.findById(skuId);
        Boolean authoritativeInStock;
        Long authoritativeVersion;

        if (snapshotOpt.isPresent()) {
            SkuStockSnapshot snapshot = snapshotOpt.get();
            long snapVer = snapshot.getInventoryVersion() != null ? snapshot.getInventoryVersion() : -1L;

            if (eventVersion > snapVer) {
                snapshot.setInStock(calcInStock);
                snapshot.setInventoryVersion(eventVersion);
                snapshot.setUpdatedAt(Instant.now());
                skuStockSnapshotRepository.save(snapshot);

                authoritativeInStock = calcInStock;
                authoritativeVersion = eventVersion;
            } else {
                log.info("Incoming stock event for SKU {} version {} <= existing snapshot version {}. Reconciling from latest snapshot.",
                        skuId, eventVersion, snapVer);
                authoritativeInStock = snapshot.getInStock();
                authoritativeVersion = snapVer;
            }
        } else {
            SkuStockSnapshot snapshot = SkuStockSnapshot.builder()
                    .skuId(skuId)
                    .inStock(calcInStock)
                    .inventoryVersion(eventVersion)
                    .updatedAt(Instant.now())
                    .build();
            skuStockSnapshotRepository.save(snapshot);

            authoritativeInStock = calcInStock;
            authoritativeVersion = eventVersion;
        }

        // 2. Synchronize / reconcile Elasticsearch doc and active reindex targets
        String skuIdStr = skuId.toString();
        Optional<ProductSkuDocument> docOpt = productSkuRepository.findById(skuIdStr);

        if (docOpt.isEmpty()) {
            log.info("SKU {} not found in Elasticsearch index yet. Stock state (inStock={}, version={}) saved in durable store for later indexing.",
                    skuId, authoritativeInStock, authoritativeVersion);
            return;
        }

        ProductSkuDocument doc = docOpt.get();
        long esVer = doc.getInventoryVersion() != null ? doc.getInventoryVersion() : -1L;

        if (authoritativeVersion != null && authoritativeVersion > esVer) {
            doc.setInStock(authoritativeInStock);
            doc.setInventoryVersion(authoritativeVersion);
            productSkuRepository.save(doc);
            syncSkuToRunningReindexTargets(doc);
            log.info("Updated/reconciled stock for SKU {} in Elasticsearch: inStock={}, version={}",
                    skuId, authoritativeInStock, authoritativeVersion);
        } else {
            log.debug("Elasticsearch document for SKU {} version {} already at or ahead of authoritative version {}",
                    skuId, esVer, authoritativeVersion);
        }
    }

    private void syncSkuToRunningReindexTargets(ProductSkuDocument doc) {
        if (reindexJobRepository == null || elasticsearchOperations == null) return;
        List<CatalogReindexJob> activeJobs = reindexJobRepository.findByStatusIn(
                List.of(ReindexJobStatus.QUEUED, ReindexJobStatus.RUNNING));
        for (CatalogReindexJob job : activeJobs) {
            if (job.getTargetIndex() != null) {
                try {
                    elasticsearchOperations.save(doc, IndexCoordinates.of(job.getTargetIndex()));
                    log.debug("Dual-wrote SKU {} to target {}", doc.getSkuId(), job.getTargetIndex());
                } catch (Exception e) {
                    failActiveReindexJob(job, "Dual-writing SKU " + doc.getSkuId() + " to target failed: " + e.getMessage());
                }
            }
        }
    }

    private void deleteSkuFromRunningReindexTargets(String skuId) {
        if (reindexJobRepository == null || elasticsearchOperations == null) return;
        List<CatalogReindexJob> activeJobs = reindexJobRepository.findByStatusIn(
                List.of(ReindexJobStatus.QUEUED, ReindexJobStatus.RUNNING));
        for (CatalogReindexJob job : activeJobs) {
            if (job.getTargetIndex() != null) {
                try {
                    elasticsearchOperations.delete(skuId, IndexCoordinates.of(job.getTargetIndex()));
                } catch (Exception e) {
                    failActiveReindexJob(job, "Deleting SKU " + skuId + " from target failed: " + e.getMessage());
                }
            }
        }
    }

    private void deleteProductFromRunningReindexTargets(String productId) {
        if (reindexJobRepository == null || elasticsearchOperations == null) return;
        List<CatalogReindexJob> activeJobs = reindexJobRepository.findByStatusIn(
                List.of(ReindexJobStatus.QUEUED, ReindexJobStatus.RUNNING));
        for (CatalogReindexJob job : activeJobs) {
            if (job.getTargetIndex() != null) {
                try {
                    co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder bq =
                            new co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder();
                    bq.filter(f -> f.term(t -> t.field("productId").value(productId)));
                    org.springframework.data.elasticsearch.client.elc.NativeQuery query =
                            org.springframework.data.elasticsearch.client.elc.NativeQuery.builder()
                                    .withQuery(bq.build()._toQuery())
                                    .build();
                    org.springframework.data.elasticsearch.core.query.DeleteQuery deleteQuery =
                            org.springframework.data.elasticsearch.core.query.DeleteQuery.builder(query).build();
                    elasticsearchOperations.delete(deleteQuery, ProductSkuDocument.class, IndexCoordinates.of(job.getTargetIndex()));
                } catch (Exception e) {
                    failActiveReindexJob(job, "Deleting product " + productId + " from target failed: " + e.getMessage());
                }
            }
        }
    }

    private void failActiveReindexJob(CatalogReindexJob job, String errorMsg) {
        try {
            log.error("Failing reindex job {} due to target dual-write failure: {}", job.getId(), errorMsg);
            job.setStatus(ReindexJobStatus.FAILED);
            job.setErrorSummary(errorMsg);
            job.setFinishedAt(Instant.now());
            reindexJobRepository.save(job);
        } catch (Exception ex) {
            log.error("Could not record failure for reindex job {}: {}", job.getId(), ex.getMessage());
        }
    }


    private Map<String, Object> parseAttributes(String attributesJson) {
        if (attributesJson == null || attributesJson.trim().isEmpty() || attributesJson.trim().equals("{}")) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(attributesJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse SKU attributes JSON: {}", e.getMessage());
            return new HashMap<>();
        }
    }
}
