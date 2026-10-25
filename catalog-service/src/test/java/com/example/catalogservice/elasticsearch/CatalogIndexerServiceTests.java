package com.example.catalogservice.elasticsearch;

import com.example.catalogservice.elasticsearch.document.ProductSkuDocument;
import com.example.catalogservice.elasticsearch.repository.ProductSkuRepository;
import com.example.catalogservice.elasticsearch.service.CatalogIndexerService;
import com.example.catalogservice.entity.*;
import com.example.catalogservice.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CatalogIndexerServiceTests {

    @Mock
    private ProductSkuRepository productSkuRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private BrandRepository brandRepository;

    @Mock
    private SkuRepository skuRepository;

    @Mock
    private ProductImageRepository productImageRepository;

    @Mock
    private CatalogReindexJobRepository reindexJobRepository;

    @Mock
    private SkuStockSnapshotRepository skuStockSnapshotRepository;

    @Mock
    private org.springframework.data.elasticsearch.core.ElasticsearchOperations elasticsearchOperations;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private CatalogIndexerService catalogIndexerService;

    private UUID productId;
    private UUID categoryId;
    private UUID brandId;
    private UUID skuId;

    @BeforeEach
    void setUp() {
        productId = UUID.randomUUID();
        categoryId = UUID.randomUUID();
        brandId = UUID.randomUUID();
        skuId = UUID.randomUUID();
    }

    @Test
    @DisplayName("Positive: Sync active product, category, and brand indexes active SKU document into Elasticsearch")
    void testSyncProductToElasticsearch_Active_IndexesDocument() {
        Product product = Product.builder()
                .id(productId)
                .name("MacBook Pro")
                .description("Apple Silicon laptop")
                .categoryId(categoryId)
                .brandId(brandId)
                .status(ProductStatus.ACTIVE)
                .build();

        Category category = Category.builder()
                .id(categoryId)
                .name("Laptops")
                .status(CategoryStatus.ACTIVE)
                .build();

        Brand brand = Brand.builder()
                .id(brandId)
                .name("Apple")
                .status(BrandStatus.ACTIVE)
                .build();

        Sku activeSku = Sku.builder()
                .id(skuId)
                .productId(productId)
                .skuCode("MBP-M3-16")
                .name("MacBook Pro M3 16GB")
                .price(new BigDecimal("45000000"))
                .currency("VND")
                .status(SkuStatus.ACTIVE)
                .attributes("{\"ram\":\"16GB\",\"ssd\":\"512GB\"}")
                .updatedAt(Instant.now())
                .build();

        ProductImage image = ProductImage.builder()
                .id(UUID.randomUUID())
                .productId(productId)
                .url("https://example.com/mbp.jpg")
                .altText("Front")
                .primary(true)
                .displayOrder(1)
                .build();

        when(productRepository.findByIdAndDeletedAtIsNull(productId)).thenReturn(Optional.of(product));
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
        when(brandRepository.findById(brandId)).thenReturn(Optional.of(brand));
        when(skuRepository.findByProductIdAndDeletedAtIsNull(productId)).thenReturn(List.of(activeSku));
        when(productImageRepository.findByProductIdOrderByDisplayOrderAscCreatedAtAsc(productId)).thenReturn(List.of(image));
        when(productSkuRepository.findById(skuId.toString())).thenReturn(Optional.empty());

        catalogIndexerService.syncProductToElasticsearch(productId, 100L);

        ArgumentCaptor<ProductSkuDocument> docCaptor = ArgumentCaptor.forClass(ProductSkuDocument.class);
        verify(productSkuRepository).save(docCaptor.capture());

        ProductSkuDocument doc = docCaptor.getValue();
        assertThat(doc.getSkuId()).isEqualTo(skuId.toString());
        assertThat(doc.getProductId()).isEqualTo(productId.toString());
        assertThat(doc.getSkuCode()).isEqualTo("MBP-M3-16");
        assertThat(doc.getProductName()).isEqualTo("MacBook Pro");
        assertThat(doc.getName()).isEqualTo("MacBook Pro M3 16GB");
        assertThat(doc.getCategoryName()).isEqualTo("Laptops");
        assertThat(doc.getBrandName()).isEqualTo("Apple");
        assertThat(doc.getPrice()).isEqualTo(45000000.0);
        assertThat(doc.getInStock()).isNull(); // Initial state is UNKNOWN
        assertThat(doc.getInventoryVersion()).isNull();
        assertThat(doc.getAggregateRevision()).isEqualTo(100L);
        assertThat(doc.getAttributes()).containsEntry("ram", "16GB");
        assertThat(doc.getImages()).hasSize(1);
        assertThat(doc.getImages().get(0).isPrimary()).isTrue();
    }

    @Test
    @DisplayName("Positive: Sync inactive product removes all SKU documents from Elasticsearch")
    void testSyncProductToElasticsearch_InactiveProduct_DeletesDocuments() {
        Product product = Product.builder()
                .id(productId)
                .status(ProductStatus.INACTIVE)
                .build();

        when(productRepository.findByIdAndDeletedAtIsNull(productId)).thenReturn(Optional.of(product));

        catalogIndexerService.syncProductToElasticsearch(productId, 101L);

        verify(productSkuRepository).deleteByProductId(productId.toString());
        verify(productSkuRepository, never()).save(any());
    }

    @Test
    @DisplayName("Positive: Sync product with inactive category removes all SKU documents from Elasticsearch")
    void testSyncProductToElasticsearch_InactiveCategory_DeletesDocuments() {
        Product product = Product.builder()
                .id(productId)
                .categoryId(categoryId)
                .brandId(brandId)
                .status(ProductStatus.ACTIVE)
                .build();

        Category inactiveCategory = Category.builder()
                .id(categoryId)
                .status(CategoryStatus.INACTIVE)
                .build();

        when(productRepository.findByIdAndDeletedAtIsNull(productId)).thenReturn(Optional.of(product));
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(inactiveCategory));

        catalogIndexerService.syncProductToElasticsearch(productId, 102L);

        verify(productSkuRepository).deleteByProductId(productId.toString());
        verify(productSkuRepository, never()).save(any());
    }

    @Test
    @DisplayName("Positive: Older aggregateRevision does not overwrite newer document in Elasticsearch")
    void testSyncProductToElasticsearch_OlderRevisionIgnored() {
        Product product = Product.builder()
                .id(productId)
                .categoryId(categoryId)
                .brandId(brandId)
                .status(ProductStatus.ACTIVE)
                .build();

        when(productRepository.findByIdAndDeletedAtIsNull(productId)).thenReturn(Optional.of(product));
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(Category.builder().id(categoryId).status(CategoryStatus.ACTIVE).build()));
        when(brandRepository.findById(brandId)).thenReturn(Optional.of(Brand.builder().id(brandId).status(BrandStatus.ACTIVE).build()));

        Sku activeSku = Sku.builder()
                .id(skuId)
                .productId(productId)
                .skuCode("MBP-1")
                .name("MacBook Pro")
                .price(BigDecimal.ONE)
                .status(SkuStatus.ACTIVE)
                .build();
        when(skuRepository.findByProductIdAndDeletedAtIsNull(productId)).thenReturn(List.of(activeSku));

        ProductSkuDocument existingDoc = ProductSkuDocument.builder()
                .skuId(skuId.toString())
                .aggregateRevision(200L) // Existing is revision 200
                .build();
        when(productSkuRepository.findById(skuId.toString())).thenReturn(Optional.of(existingDoc));

        // Incoming is older revision 150
        catalogIndexerService.syncProductToElasticsearch(productId, 150L);

        verify(productSkuRepository, never()).save(any());
    }

    @Test
    @DisplayName("Positive: Stock change event updates inStock and inventoryVersion monotonically")
    void testSyncStockToElasticsearch_MonotonicUpdate() {
        ProductSkuDocument existing = ProductSkuDocument.builder()
                .skuId(skuId.toString())
                .inStock(null) // UNKNOWN initially
                .inventoryVersion(10L)
                .build();
        when(productSkuRepository.findById(skuId.toString())).thenReturn(Optional.of(existing));

        // Incoming higher version 11
        catalogIndexerService.syncStockToElasticsearch(skuId, 5L, 11L, true);

        assertThat(existing.getInStock()).isTrue();
        assertThat(existing.getInventoryVersion()).isEqualTo(11L);
        verify(productSkuRepository).save(existing);
    }

    @Test
    @DisplayName("Negative: Stale stock change event (version <= existing) is ignored")
    void testSyncStockToElasticsearch_StaleVersionIgnored() {
        ProductSkuDocument existing = ProductSkuDocument.builder()
                .skuId(skuId.toString())
                .inStock(true)
                .inventoryVersion(20L)
                .build();
        when(productSkuRepository.findById(skuId.toString())).thenReturn(Optional.of(existing));

        // Incoming stale version 19
        catalogIndexerService.syncStockToElasticsearch(skuId, 0L, 19L, false);

        // Document not updated
        assertThat(existing.getInStock()).isTrue();
        assertThat(existing.getInventoryVersion()).isEqualTo(20L);
        verify(productSkuRepository, never()).save(any());
    }

    @Test
    @DisplayName("Positive: Stock event for absent ES document is saved into SkuStockSnapshot durable store")
    void testSyncStockToElasticsearch_DocumentAbsent_PersistsSnapshotInDurableStore() {
        when(productSkuRepository.findById(skuId.toString())).thenReturn(Optional.empty());
        when(skuStockSnapshotRepository.findById(skuId)).thenReturn(Optional.empty());

        catalogIndexerService.syncStockToElasticsearch(skuId, 10L, 1L, true);

        ArgumentCaptor<SkuStockSnapshot> captor = ArgumentCaptor.forClass(SkuStockSnapshot.class);
        verify(skuStockSnapshotRepository).save(captor.capture());

        SkuStockSnapshot saved = captor.getValue();
        assertThat(saved.getSkuId()).isEqualTo(skuId);
        assertThat(saved.getInStock()).isTrue();
        assertThat(saved.getInventoryVersion()).isEqualTo(1L);
        verify(productSkuRepository, never()).save(any());
    }

    @Test
    @DisplayName("Positive: Product indexing applies pre-existing SkuStockSnapshot when ES document was absent")
    void testSyncProductToElasticsearch_OutOfOrderStockEvent_AppliesPersistedStockSnapshot() {
        Product product = Product.builder()
                .id(productId)
                .name("MacBook Pro")
                .description("Apple Silicon laptop")
                .categoryId(categoryId)
                .brandId(brandId)
                .status(ProductStatus.ACTIVE)
                .build();

        Category category = Category.builder()
                .id(categoryId)
                .name("Laptops")
                .status(CategoryStatus.ACTIVE)
                .build();

        Brand brand = Brand.builder()
                .id(brandId)
                .name("Apple")
                .status(BrandStatus.ACTIVE)
                .build();

        Sku activeSku = Sku.builder()
                .id(skuId)
                .productId(productId)
                .skuCode("MBP-PRO-1")
                .name("MacBook Pro 16")
                .price(new BigDecimal("45000000"))
                .currency("VND")
                .status(SkuStatus.ACTIVE)
                .build();

        when(productRepository.findByIdAndDeletedAtIsNull(productId)).thenReturn(Optional.of(product));
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
        when(brandRepository.findById(brandId)).thenReturn(Optional.of(brand));
        when(skuRepository.findByProductIdAndDeletedAtIsNull(productId)).thenReturn(List.of(activeSku));
        when(productSkuRepository.findById(skuId.toString())).thenReturn(Optional.empty());

        // Stock event arrived before product indexing
        SkuStockSnapshot snapshot = SkuStockSnapshot.builder()
                .skuId(skuId)
                .inStock(true)
                .inventoryVersion(5L)
                .updatedAt(Instant.now())
                .build();
        when(skuStockSnapshotRepository.findById(skuId)).thenReturn(Optional.of(snapshot));

        catalogIndexerService.syncProductToElasticsearch(productId, 1L);

        ArgumentCaptor<ProductSkuDocument> captor = ArgumentCaptor.forClass(ProductSkuDocument.class);
        verify(productSkuRepository).save(captor.capture());

        ProductSkuDocument indexedDoc = captor.getValue();
        assertThat(indexedDoc.getInStock()).isTrue();
        assertThat(indexedDoc.getInventoryVersion()).isEqualTo(5L);
    }

    @Test
    @DisplayName("Negative: Stale stock event does not overwrite newer SkuStockSnapshot")
    void testSyncStockToElasticsearch_StaleEvent_DoesNotOverwriteNewerSnapshot() {
        SkuStockSnapshot existingSnap = SkuStockSnapshot.builder()
                .skuId(skuId)
                .inStock(true)
                .inventoryVersion(10L)
                .updatedAt(Instant.now())
                .build();
        when(skuStockSnapshotRepository.findById(skuId)).thenReturn(Optional.of(existingSnap));

        // Incoming version 8 < 10
        catalogIndexerService.syncStockToElasticsearch(skuId, 0L, 8L, false);

        verify(skuStockSnapshotRepository, never()).save(any());
    }

    @Test
    @DisplayName("Positive: Stale stock event reconciles lagging Elasticsearch doc from latest durable snapshot")
    void testSyncStockToElasticsearch_StaleEvent_ReconcilesLaggingElasticsearchDoc() {
        // Snapshot already at version 10 (e.g. from earlier event whose ES write failed)
        SkuStockSnapshot snapshot = SkuStockSnapshot.builder()
                .skuId(skuId)
                .inStock(true)
                .inventoryVersion(10L)
                .updatedAt(Instant.now())
                .build();
        when(skuStockSnapshotRepository.findById(skuId)).thenReturn(Optional.of(snapshot));

        // Elasticsearch doc currently lags at version 5
        ProductSkuDocument laggingDoc = ProductSkuDocument.builder()
                .skuId(skuId.toString())
                .inStock(false)
                .inventoryVersion(5L)
                .build();
        when(productSkuRepository.findById(skuId.toString())).thenReturn(Optional.of(laggingDoc));

        // Reindex job currently running
        CatalogReindexJob runningJob = CatalogReindexJob.builder()
                .id(UUID.randomUUID())
                .status(ReindexJobStatus.RUNNING)
                .targetIndex("product_catalog-v2")
                .build();
        when(reindexJobRepository.findByStatusIn(anyList())).thenReturn(List.of(runningJob));

        // Stale/replayed event arrives with version 8 (8 < 10)
        catalogIndexerService.syncStockToElasticsearch(skuId, 0L, 8L, false);

        // Snapshot is NOT regressed to 8
        verify(skuStockSnapshotRepository, never()).save(any());
        assertThat(snapshot.getInventoryVersion()).isEqualTo(10L);

        // Elasticsearch document is reconciled to version 10 and inStock=true from authoritative snapshot
        ArgumentCaptor<ProductSkuDocument> docCaptor = ArgumentCaptor.forClass(ProductSkuDocument.class);
        verify(productSkuRepository).save(docCaptor.capture());
        ProductSkuDocument reconciledDoc = docCaptor.getValue();
        assertThat(reconciledDoc.getInStock()).isTrue();
        assertThat(reconciledDoc.getInventoryVersion()).isEqualTo(10L);

        // Running reindex target is also reconciled
        verify(elasticsearchOperations).save(reconciledDoc, IndexCoordinates.of("product_catalog-v2"));
    }
}
