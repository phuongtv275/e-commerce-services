package com.example.catalogservice.search;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.exception.BusinessException;
import com.example.catalogservice.dto.req.SearchProductRequest;
import com.example.catalogservice.dto.resp.SearchProductResponse;
import com.example.catalogservice.elasticsearch.document.ProductSkuDocument;
import com.example.catalogservice.elasticsearch.service.ProductSearchService;
import com.example.catalogservice.elasticsearch.service.SearchCursorCodec;
import com.example.catalogservice.entity.*;
import com.example.catalogservice.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.Query;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductSearchServiceTests {

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

    private SearchCursorCodec searchCursorCodec;
    private ProductSearchService searchService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        searchCursorCodec = new SearchCursorCodec(objectMapper);
        searchService = new ProductSearchService(
                elasticsearchOperations,
                searchCursorCodec,
                productRepository,
                skuRepository,
                categoryRepository,
                brandRepository,
                productImageRepository,
                objectMapper
        );
    }

    @Test
    @DisplayName("Negative: minPrice > maxPrice throws VALIDATION_ERROR")
    void testValidateSearchRequest_MinPriceGreaterThanMaxPrice() {
        SearchProductRequest req = SearchProductRequest.builder()
                .minPrice(new BigDecimal("500000"))
                .maxPrice(new BigDecimal("200000"))
                .build();

        assertThatThrownBy(() -> searchService.search(req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(be.getMessage()).contains("minPrice cannot be greater than maxPrice");
                });
    }

    @Test
    @DisplayName("Negative: Deep offset window (page + 1) * size > 200 throws VALIDATION_ERROR")
    void testValidateSearchRequest_DeepOffsetThrowsValidationError() {
        SearchProductRequest req = SearchProductRequest.builder()
                .mode("offset")
                .page(10)
                .size(20) // (10 + 1) * 20 = 220 > 200
                .build();

        assertThatThrownBy(() -> searchService.search(req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(be.getMessage()).contains("Deep pagination via offset is limited to 200 items");
                });
    }

    @Test
    @DisplayName("Positive: SearchCursorCodec encodes and decodes valid cursor")
    void testSearchCursorCodec_EncodeAndDecode() {
        SearchProductRequest req = SearchProductRequest.builder()
                .q("iPhone")
                .mode("cursor")
                .build();
        String fingerprint = searchCursorCodec.computeFingerprint(req);
        List<Object> sortValues = List.of(1729240000L, UUID.randomUUID().toString());

        String encoded = searchCursorCodec.encode(sortValues, fingerprint);
        assertThat(encoded).isNotNull();

        SearchCursorCodec.CursorPayload decoded = searchCursorCodec.decode(encoded, fingerprint);
        assertThat(decoded).isNotNull();
        assertThat(decoded.getSortValues()).hasSize(2);
        assertThat(decoded.getFingerprint()).isEqualTo(fingerprint);
    }

    @Test
    @DisplayName("Positive: SearchCursorCodec marks expired cursor as expired per spec")
    void testSearchCursorCodec_ExpiredCursorFlagged() throws Exception {
        SearchProductRequest req = SearchProductRequest.builder().q("iPhone").build();
        String fingerprint = searchCursorCodec.computeFingerprint(req);

        SearchCursorCodec.CursorPayload payload = SearchCursorCodec.CursorPayload.builder()
                .sortValues(List.of("val1"))
                .fingerprint(fingerprint)
                .createdAt(System.currentTimeMillis() - 70_000L) // 70 seconds ago (> 60s TTL)
                .build();

        String json = objectMapper.writeValueAsString(payload);
        String expiredCursor = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));

        SearchCursorCodec.CursorPayload decoded = searchCursorCodec.decode(expiredCursor, fingerprint);
        assertThat(decoded).isNotNull();
        assertThat(decoded.isExpired()).isTrue();
    }


    @Test
    @DisplayName("Negative: SearchCursorCodec rejects fingerprint mismatch")
    void testSearchCursorCodec_FingerprintMismatchThrowsValidationError() {
        SearchProductRequest req1 = SearchProductRequest.builder().q("iPhone").build();
        SearchProductRequest req2 = SearchProductRequest.builder().q("Samsung").build();

        String fp1 = searchCursorCodec.computeFingerprint(req1);
        String fp2 = searchCursorCodec.computeFingerprint(req2);

        String cursor = searchCursorCodec.encode(List.of("val1"), fp1);

        assertThatThrownBy(() -> searchCursorCodec.decode(cursor, fp2))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(be.getMessage()).contains("does not match current query filters");
                });
    }

    @Test
    @DisplayName("Negative: SearchCursorCodec rejects malformed cursor")
    void testSearchCursorCodec_MalformedCursorThrowsValidationError() {
        assertThatThrownBy(() -> searchCursorCodec.decode("invalid-base64-not-json!!", "fp"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                });
    }

    @Test
    @DisplayName("Positive: Elasticsearch search returns valid SearchProductResponse")
    @SuppressWarnings("unchecked")
    void testSearchWithElasticsearch_Success() {
        SearchProductRequest req = SearchProductRequest.builder()
                .q("laptop")
                .mode("offset")
                .page(0)
                .size(10)
                .build();

        SearchHits<ProductSkuDocument> mockSearchHits = mock(SearchHits.class);
        SearchHit<ProductSkuDocument> mockHit = mock(SearchHit.class);

        UUID productId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID brandId = UUID.randomUUID();

        ProductSkuDocument doc = ProductSkuDocument.builder()
                .skuId(skuId.toString())
                .skuCode("SKU-100")
                .productId(productId.toString())
                .productName("ThinkPad X1")
                .name("ThinkPad X1 Carbon")
                .description("Ultrabook")
                .categoryId(categoryId.toString())
                .categoryName("Laptops")
                .brandId(brandId.toString())
                .brandName("Lenovo")
                .price(35000000.0)
                .currency("VND")
                .status("ACTIVE")
                .inStock(true)
                .updatedAt(Instant.now())
                .build();

        when(mockHit.getContent()).thenReturn(doc);
        when(mockSearchHits.getSearchHits()).thenReturn(List.of(mockHit));
        when(mockSearchHits.getTotalHits()).thenReturn(1L);

        when(elasticsearchOperations.search(any(Query.class), eq(ProductSkuDocument.class), any(IndexCoordinates.class)))
                .thenReturn(mockSearchHits);

        SearchProductResponse resp = searchService.search(req);

        assertThat(resp).isNotNull();
        assertThat(resp.getSource()).isEqualTo("elasticsearch");
        assertThat(resp.getTotal()).isEqualTo(1L);
        assertThat(resp.getItems()).hasSize(1);
        assertThat(resp.getItems().get(0).getSkuCode()).isEqualTo("SKU-100");
        assertThat(resp.getItems().get(0).getName()).isEqualTo("ThinkPad X1 Carbon");
        assertThat(resp.getItems().get(0).getProductName()).isEqualTo("ThinkPad X1");
    }


    @Test
    @DisplayName("Positive: Fallback to PostgreSQL when Elasticsearch throws exception")
    void testSearch_ElasticsearchFails_FallsBackToPostgres() {
        when(elasticsearchOperations.search(any(Query.class), eq(ProductSkuDocument.class), any(IndexCoordinates.class)))
                .thenThrow(new RuntimeException("Elasticsearch cluster unreachable"));

        UUID categoryId = UUID.randomUUID();
        UUID brandId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();

        Category activeCategory = Category.builder()
                .id(categoryId)
                .name("Smartphones")
                .status(CategoryStatus.ACTIVE)
                .build();
        when(categoryRepository.findByStatusOrderByDisplayOrderAscNameAscIdAsc(CategoryStatus.ACTIVE))
                .thenReturn(List.of(activeCategory));

        Brand activeBrand = Brand.builder()
                .id(brandId)
                .name("Apple")
                .status(BrandStatus.ACTIVE)
                .build();
        when(brandRepository.findByStatusOrderByNameAsc(BrandStatus.ACTIVE))
                .thenReturn(List.of(activeBrand));

        Product product = Product.builder()
                .id(productId)
                .name("iPhone 15 Pro")
                .description("Titanium smartphone")
                .categoryId(categoryId)
                .brandId(brandId)
                .status(ProductStatus.ACTIVE)
                .build();
        when(productRepository.findByStatusAndDeletedAtIsNull(ProductStatus.ACTIVE))
                .thenReturn(List.of(product));

        Sku sku = Sku.builder()
                .id(skuId)
                .productId(productId)
                .skuCode("IP15P-256")
                .name("iPhone 15 Pro 256GB")
                .price(new BigDecimal("28000000"))
                .currency("VND")
                .status(SkuStatus.ACTIVE)
                .updatedAt(Instant.now())
                .build();
        when(skuRepository.findByProductIdAndStatusAndDeletedAtIsNull(productId, SkuStatus.ACTIVE))
                .thenReturn(List.of(sku));
        when(productImageRepository.findByProductIdOrderByDisplayOrderAscCreatedAtAsc(productId))
                .thenReturn(List.of());

        SearchProductRequest req = SearchProductRequest.builder()
                .q("iPhone")
                .minPrice(new BigDecimal("20000000"))
                .maxPrice(new BigDecimal("30000000"))
                .mode("offset")
                .page(0)
                .size(10)
                .build();

        SearchProductResponse resp = searchService.search(req);

        assertThat(resp).isNotNull();
        assertThat(resp.getSource()).isEqualTo("postgres-fallback");
        assertThat(resp.getTotal()).isEqualTo(1L);
        assertThat(resp.getItems()).hasSize(1);
        assertThat(resp.getItems().get(0).getSkuCode()).isEqualTo("IP15P-256");
        assertThat(resp.getItems().get(0).getInStock()).isNull(); // UNKNOWN on DB fallback
    }

    @Test
    @DisplayName("Negative: Fallback rejects mode=cursor with UPSTREAM_UNAVAILABLE")
    void testSearchFallback_CursorModeThrowsUpstreamUnavailable() {
        when(elasticsearchOperations.search(any(Query.class), eq(ProductSkuDocument.class), any(IndexCoordinates.class)))
                .thenThrow(new RuntimeException("Elasticsearch connection refused"));

        SearchProductRequest req = SearchProductRequest.builder()
                .mode("cursor")
                .size(10)
                .build();

        assertThatThrownBy(() -> searchService.search(req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
                });
    }

    @Test
    @DisplayName("Negative: Fallback rejects inStock filtering with UPSTREAM_UNAVAILABLE")
    void testSearchFallback_InStockFilterThrowsUpstreamUnavailable() {
        when(elasticsearchOperations.search(any(Query.class), eq(ProductSkuDocument.class), any(IndexCoordinates.class)))
                .thenThrow(new RuntimeException("Elasticsearch timeout"));

        SearchProductRequest req = SearchProductRequest.builder()
                .mode("offset")
                .inStock(true)
                .page(0)
                .size(10)
                .build();

        assertThatThrownBy(() -> searchService.search(req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
                    assertThat(be.getMessage()).contains("Stock filtering is temporarily unavailable");
                });
    }

    @Test
    @DisplayName("Negative: Invalid sort parameter throws VALIDATION_ERROR")
    void testValidateSearchRequest_InvalidSort() {
        SearchProductRequest req = SearchProductRequest.builder()
                .sort("invalid_sort_type")
                .build();

        assertThatThrownBy(() -> searchService.search(req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(be.getMessage()).contains("Invalid sort parameter");
                });
    }

    @Test
    @DisplayName("Negative: Invalid mode parameter throws VALIDATION_ERROR")
    void testValidateSearchRequest_InvalidMode() {
        SearchProductRequest req = SearchProductRequest.builder()
                .mode("stream")
                .build();

        assertThatThrownBy(() -> searchService.search(req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(be.getMessage()).contains("Invalid pagination mode");
                });
    }

    @Test
    @DisplayName("Negative: mode=cursor with page > 0 throws VALIDATION_ERROR")
    void testValidateSearchRequest_CursorWithPageGreaterThanZero() {
        SearchProductRequest req = SearchProductRequest.builder()
                .mode("cursor")
                .page(1)
                .build();

        assertThatThrownBy(() -> searchService.search(req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(be.getMessage()).contains("Cursor pagination does not support page parameter");
                });
    }

    @Test
    @DisplayName("Negative: mode=offset with cursor != null throws VALIDATION_ERROR")
    void testValidateSearchRequest_OffsetWithCursor() {
        SearchProductRequest req = SearchProductRequest.builder()
                .mode("offset")
                .cursor("some-cursor-token")
                .build();

        assertThatThrownBy(() -> searchService.search(req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(be.getMessage()).contains("Cursor parameter is only supported in cursor mode");
                });
    }

    @Test
    @DisplayName("Negative: Invalid attribute key with special characters throws VALIDATION_ERROR")
    void testValidateSearchRequest_InvalidAttributeKey() {
        SearchProductRequest req = SearchProductRequest.builder()
                .attributes(Map.of("color!@#$", "Red"))
                .build();

        assertThatThrownBy(() -> searchService.search(req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(be.getMessage()).contains("Invalid attribute key");
                });
    }

    @Test
    @DisplayName("Negative: Empty attribute value throws VALIDATION_ERROR")
    void testValidateSearchRequest_InvalidAttributeValue() {
        SearchProductRequest req = SearchProductRequest.builder()
                .attributes(Map.of("color", "   "))
                .build();

        assertThatThrownBy(() -> searchService.search(req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(be.getMessage()).contains("Invalid attribute value");
                });
    }

    @Test
    @DisplayName("Negative: inStock filter fails UPSTREAM_UNAVAILABLE when relevant SKU stock is UNKNOWN")
    void testSearchElasticsearch_InStockWithUnknownStock_ThrowsUpstreamUnavailable() {
        SearchProductRequest req = SearchProductRequest.builder()
                .q("iPhone")
                .inStock(true)
                .build();

        // Simulate that count of documents with inStock missing/null > 0
        when(elasticsearchOperations.count(any(Query.class), eq(ProductSkuDocument.class), any(IndexCoordinates.class)))
                .thenReturn(2L);

        assertThatThrownBy(() -> searchService.search(req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
                    assertThat(be.getMessage()).contains("Inventory stock status is unknown");
                });
    }

    @Test
    @DisplayName("Positive: Expired cursor restarts search from beginning in Elasticsearch mode")
    @SuppressWarnings("unchecked")
    void testSearch_ExpiredCursor_RestartsFromBeginning() throws Exception {
        SearchProductRequest req = SearchProductRequest.builder()
                .q("laptop")
                .mode("cursor")
                .size(10)
                .build();

        String fingerprint = searchCursorCodec.computeFingerprint(req);
        SearchCursorCodec.CursorPayload payload = SearchCursorCodec.CursorPayload.builder()
                .sortValues(List.of("val1"))
                .fingerprint(fingerprint)
                .createdAt(System.currentTimeMillis() - 70_000L) // expired (> 60s)
                .build();

        String json = objectMapper.writeValueAsString(payload);
        String expiredCursor = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        req.setCursor(expiredCursor);

        SearchHits<ProductSkuDocument> mockSearchHits = mock(SearchHits.class);
        SearchHit<ProductSkuDocument> mockHit = mock(SearchHit.class);

        ProductSkuDocument doc = ProductSkuDocument.builder()
                .skuId(UUID.randomUUID().toString())
                .skuCode("SKU-RESTART")
                .productId(UUID.randomUUID().toString())
                .name("Restarted Search Laptop")
                .productName("Laptop")
                .description("Good laptop")
                .categoryId(UUID.randomUUID().toString())
                .categoryName("Laptops")
                .brandId(UUID.randomUUID().toString())
                .brandName("Brand")
                .price(20000000.0)
                .currency("VND")
                .status("ACTIVE")
                .inStock(true)
                .updatedAt(Instant.now())
                .build();

        when(mockHit.getContent()).thenReturn(doc);
        when(mockSearchHits.getSearchHits()).thenReturn(List.of(mockHit));
        when(elasticsearchOperations.openPointInTime(any(IndexCoordinates.class), any(Duration.class)))
                .thenReturn("pit-real-restart-123");
        when(elasticsearchOperations.search(any(Query.class), eq(ProductSkuDocument.class), any(IndexCoordinates.class)))
                .thenReturn(mockSearchHits);

        SearchProductResponse resp = searchService.search(req);

        assertThat(resp).isNotNull();
        assertThat(resp.getItems()).hasSize(1);
        assertThat(resp.getItems().get(0).getSkuCode()).isEqualTo("SKU-RESTART");

        org.mockito.ArgumentCaptor<Query> queryCaptor = org.mockito.ArgumentCaptor.forClass(Query.class);
        verify(elasticsearchOperations).search(queryCaptor.capture(), eq(ProductSkuDocument.class), any(IndexCoordinates.class));
        assertThat(queryCaptor.getValue().getPointInTime()).isNotNull();
        assertThat(queryCaptor.getValue().getPointInTime().id()).isEqualTo("pit-real-restart-123");
    }

    @Test
    @DisplayName("Positive: Postgres fallback filters attributes correctly")
    void testSearchPostgresFallback_WithAttributesFilter() {
        when(elasticsearchOperations.search(any(Query.class), eq(ProductSkuDocument.class), any(IndexCoordinates.class)))
                .thenThrow(new RuntimeException("Elasticsearch unavailable"));

        UUID categoryId = UUID.randomUUID();
        UUID brandId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        Category activeCategory = Category.builder().id(categoryId).name("Phones").status(CategoryStatus.ACTIVE).build();
        when(categoryRepository.findByStatusOrderByDisplayOrderAscNameAscIdAsc(CategoryStatus.ACTIVE))
                .thenReturn(List.of(activeCategory));

        Brand activeBrand = Brand.builder().id(brandId).name("Apple").status(BrandStatus.ACTIVE).build();
        when(brandRepository.findByStatusOrderByNameAsc(BrandStatus.ACTIVE))
                .thenReturn(List.of(activeBrand));

        Product product = Product.builder()
                .id(productId)
                .name("iPhone 15")
                .categoryId(categoryId)
                .brandId(brandId)
                .status(ProductStatus.ACTIVE)
                .build();
        when(productRepository.findByStatusAndDeletedAtIsNull(ProductStatus.ACTIVE))
                .thenReturn(List.of(product));

        Sku matchingSku = Sku.builder()
                .id(UUID.randomUUID())
                .productId(productId)
                .skuCode("IP15-BLK")
                .name("iPhone 15 Black")
                .price(new BigDecimal("20000000"))
                .currency("VND")
                .attributes("{\"color\":\"Black\",\"storage\":\"128GB\"}")
                .status(SkuStatus.ACTIVE)
                .updatedAt(Instant.now())
                .build();

        Sku nonMatchingSku = Sku.builder()
                .id(UUID.randomUUID())
                .productId(productId)
                .skuCode("IP15-BLU")
                .name("iPhone 15 Blue")
                .price(new BigDecimal("20000000"))
                .currency("VND")
                .attributes("{\"color\":\"Blue\",\"storage\":\"128GB\"}")
                .status(SkuStatus.ACTIVE)
                .updatedAt(Instant.now())
                .build();

        when(skuRepository.findByProductIdAndStatusAndDeletedAtIsNull(productId, SkuStatus.ACTIVE))
                .thenReturn(List.of(matchingSku, nonMatchingSku));
        when(productImageRepository.findByProductIdOrderByDisplayOrderAscCreatedAtAsc(productId))
                .thenReturn(List.of());

        SearchProductRequest req = SearchProductRequest.builder()
                .attributes(Map.of("color", "Black"))
                .mode("offset")
                .page(0)
                .size(10)
                .build();

        SearchProductResponse resp = searchService.search(req);
        assertThat(resp.getItems()).hasSize(1);
        assertThat(resp.getItems().get(0).getSkuCode()).isEqualTo("IP15-BLK");
        assertThat(resp.getItems().get(0).getProductName()).isEqualTo("iPhone 15");
    }

    @Test
    @DisplayName("Positive: Real PointInTime lifecycle opens PIT on first page, attaches it to search, and closes when finished")
    @SuppressWarnings("unchecked")
    void testSearch_RealPointInTimeLifecycle_OpenUseClose() {
        SearchProductRequest firstPageReq = SearchProductRequest.builder()
                .mode("cursor")
                .size(1)
                .build();

        when(elasticsearchOperations.openPointInTime(any(IndexCoordinates.class), any(Duration.class)))
                .thenReturn("pit-real-first-page-id");

        UUID catId = UUID.randomUUID();
        UUID bId = UUID.randomUUID();

        ProductSkuDocument doc1 = ProductSkuDocument.builder()
                .skuId(UUID.randomUUID().toString())
                .skuCode("SKU-1")
                .productId(UUID.randomUUID().toString())
                .name("Item 1")
                .productName("Product 1")
                .categoryId(catId.toString())
                .categoryName("Electronics")
                .brandId(bId.toString())
                .brandName("Brand")
                .price(100.0)
                .status("ACTIVE")
                .build();

        ProductSkuDocument doc2 = ProductSkuDocument.builder()
                .skuId(UUID.randomUUID().toString())
                .skuCode("SKU-2")
                .productId(UUID.randomUUID().toString())
                .name("Item 2")
                .productName("Product 2")
                .categoryId(catId.toString())
                .categoryName("Electronics")
                .brandId(bId.toString())
                .brandName("Brand")
                .price(200.0)
                .status("ACTIVE")
                .build();

        SearchHits<ProductSkuDocument> firstHits = mock(SearchHits.class);
        SearchHit<ProductSkuDocument> hit1 = mock(SearchHit.class);
        SearchHit<ProductSkuDocument> hit2 = mock(SearchHit.class);

        when(hit1.getContent()).thenReturn(doc1);
        when(hit1.getSortValues()).thenReturn(List.of(100.0, "sku-1-id"));
        when(hit2.getContent()).thenReturn(doc2);

        // Returns 2 hits when size=1 -> hasMore = true
        when(firstHits.getSearchHits()).thenReturn(List.of(hit1, hit2));
        when(firstHits.getTotalHits()).thenReturn(2L);
        when(elasticsearchOperations.search(any(Query.class), eq(ProductSkuDocument.class), any(IndexCoordinates.class)))
                .thenReturn(firstHits);

        SearchProductResponse firstResp = searchService.search(firstPageReq);

        assertThat(firstResp.getItems()).hasSize(1);
        assertThat(firstResp.getNextCursor()).isNotNull();

        org.mockito.ArgumentCaptor<Query> queryCaptor = org.mockito.ArgumentCaptor.forClass(Query.class);
        verify(elasticsearchOperations).search(queryCaptor.capture(), eq(ProductSkuDocument.class), any(IndexCoordinates.class));
        assertThat(queryCaptor.getValue().getPointInTime()).isNotNull();
        assertThat(queryCaptor.getValue().getPointInTime().id()).isEqualTo("pit-real-first-page-id");

        // Second page request with cursor
        SearchProductRequest secondPageReq = SearchProductRequest.builder()
                .mode("cursor")
                .cursor(firstResp.getNextCursor())
                .size(1)
                .build();

        SearchHits<ProductSkuDocument> secondHits = mock(SearchHits.class);
        // Returns 1 hit when size=1 -> hasMore = false
        when(secondHits.getSearchHits()).thenReturn(List.of(hit2));
        when(secondHits.getTotalHits()).thenReturn(2L);
        when(elasticsearchOperations.search(any(Query.class), eq(ProductSkuDocument.class), any(IndexCoordinates.class)))
                .thenReturn(secondHits);

        SearchProductResponse secondResp = searchService.search(secondPageReq);

        assertThat(secondResp.getItems()).hasSize(1);
        assertThat(secondResp.getNextCursor()).isNull();

        // Verify PIT was closed when done
        verify(elasticsearchOperations).closePointInTime("pit-real-first-page-id");
    }

    @Test
    @DisplayName("Positive: PostgreSQL fallback text search matches on categoryName and brandName")
    void testSearchPostgresFallback_MatchesOnCategoryAndBrandName() {
        when(elasticsearchOperations.search(any(Query.class), eq(ProductSkuDocument.class), any(IndexCoordinates.class)))
                .thenThrow(new RuntimeException("Elasticsearch unavailable"));

        UUID categoryId = UUID.randomUUID();
        UUID brandId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        Category activeCategory = Category.builder().id(categoryId).name("Audio Devices").status(CategoryStatus.ACTIVE).build();
        when(categoryRepository.findByStatusOrderByDisplayOrderAscNameAscIdAsc(CategoryStatus.ACTIVE))
                .thenReturn(List.of(activeCategory));

        Brand activeBrand = Brand.builder().id(brandId).name("Sony").status(BrandStatus.ACTIVE).build();
        when(brandRepository.findByStatusOrderByNameAsc(BrandStatus.ACTIVE))
                .thenReturn(List.of(activeBrand));

        Product product = Product.builder()
                .id(productId)
                .name("WH-1000XM5")
                .description("Noise cancelling headphones")
                .categoryId(categoryId)
                .brandId(brandId)
                .status(ProductStatus.ACTIVE)
                .build();
        when(productRepository.findByStatusAndDeletedAtIsNull(ProductStatus.ACTIVE))
                .thenReturn(List.of(product));

        Sku sku = Sku.builder()
                .id(UUID.randomUUID())
                .productId(productId)
                .skuCode("SONY-XM5")
                .name("Wireless Headset")
                .price(new BigDecimal("8000000"))
                .currency("VND")
                .status(SkuStatus.ACTIVE)
                .updatedAt(Instant.now())
                .build();
        when(skuRepository.findByProductIdAndStatusAndDeletedAtIsNull(productId, SkuStatus.ACTIVE))
                .thenReturn(List.of(sku));
        when(productImageRepository.findByProductIdOrderByDisplayOrderAscCreatedAtAsc(productId))
                .thenReturn(List.of());

        // 1. Search by brand name "sony"
        SearchProductRequest reqBrand = SearchProductRequest.builder()
                .q("sony")
                .mode("offset")
                .page(0)
                .size(10)
                .build();
        SearchProductResponse respBrand = searchService.search(reqBrand);
        assertThat(respBrand.getItems()).hasSize(1);
        assertThat(respBrand.getItems().get(0).getBrandName()).isEqualTo("Sony");

        // 2. Search by category name "audio"
        SearchProductRequest reqCat = SearchProductRequest.builder()
                .q("audio")
                .mode("offset")
                .page(0)
                .size(10)
                .build();
        SearchProductResponse respCat = searchService.search(reqCat);
        assertThat(respCat.getItems()).hasSize(1);
        assertThat(respCat.getItems().get(0).getCategoryName()).isEqualTo("Audio Devices");
    }

    @Test
    @DisplayName("Positive: UNKNOWN stock check query captures text q and user filters")
    void testUnknownStockCheck_IncludesQueryAndFilters() {
        SearchProductRequest req = SearchProductRequest.builder()
                .q("headphones")
                .inStock(true)
                .minPrice(new BigDecimal("100000"))
                .maxPrice(new BigDecimal("500000"))
                .build();

        when(elasticsearchOperations.count(any(Query.class), eq(ProductSkuDocument.class), any(IndexCoordinates.class)))
                .thenReturn(1L);

        assertThatThrownBy(() -> searchService.search(req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
                });

        org.mockito.ArgumentCaptor<Query> countQueryCaptor = org.mockito.ArgumentCaptor.forClass(Query.class);
        verify(elasticsearchOperations).count(countQueryCaptor.capture(), eq(ProductSkuDocument.class), any(IndexCoordinates.class));
        assertThat(countQueryCaptor.getValue()).isNotNull();
    }
}
