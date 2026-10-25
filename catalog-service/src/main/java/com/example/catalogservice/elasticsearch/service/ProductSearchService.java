package com.example.catalogservice.elasticsearch.service;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.exception.BusinessException;
import com.example.catalogservice.dto.req.SearchProductRequest;
import com.example.catalogservice.dto.resp.ProductImageResponse;
import com.example.catalogservice.dto.resp.ProductSearchItem;
import com.example.catalogservice.dto.resp.SearchProductResponse;
import com.example.catalogservice.elasticsearch.document.ProductSkuDocument;
import com.example.catalogservice.entity.*;
import com.example.catalogservice.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductSearchService {

    private final ElasticsearchOperations elasticsearchOperations;
    private final SearchCursorCodec searchCursorCodec;
    private final ProductRepository productRepository;
    private final SkuRepository skuRepository;
    private final CategoryRepository categoryRepository;
    private final BrandRepository brandRepository;
    private final ProductImageRepository productImageRepository;
    private final ObjectMapper objectMapper;

    private static final Set<String> VALID_SORTS = Set.of("relevance", "price_asc", "price_desc", "newest");
    private static final Set<String> VALID_MODES = Set.of("offset", "cursor");
    private static final Pattern ATTR_KEY_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,50}$");

    public SearchProductResponse search(SearchProductRequest req) {
        validateSearchRequest(req);

        try {
            return searchWithElasticsearch(req);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.warn("Elasticsearch search query failed ({}), falling back to PostgreSQL: {}",
                    e.getClass().getSimpleName(), e.getMessage());
            return searchWithPostgresFallback(req);
        }
    }

    private void validateSearchRequest(SearchProductRequest req) {
        if (req.getMinPrice() != null && req.getMaxPrice() != null &&
                req.getMinPrice().compareTo(req.getMaxPrice()) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "minPrice cannot be greater than maxPrice");
        }

        String mode = req.getMode() != null ? req.getMode().toLowerCase() : "offset";
        if (!VALID_MODES.contains(mode)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Invalid pagination mode: " + req.getMode());
        }

        String sort = req.getSort() != null ? req.getSort().toLowerCase() : "relevance";
        if (!VALID_SORTS.contains(sort)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Invalid sort parameter: " + req.getSort());
        }

        if ("cursor".equalsIgnoreCase(mode)) {
            if (req.getPage() > 0) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "Cursor pagination does not support page parameter; page must be 0");
            }
        } else {
            if (req.getCursor() != null && !req.getCursor().trim().isEmpty()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "Cursor parameter is only supported in cursor mode");
            }
            long window = (long) (req.getPage() + 1) * req.getSize();
            if (window > 200) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "Deep pagination via offset is limited to 200 items. Please use cursor mode.");
            }
        }

        if (req.getAttributes() != null) {
            for (Map.Entry<String, String> entry : req.getAttributes().entrySet()) {
                String key = entry.getKey();
                String val = entry.getValue();
                if (key == null || !ATTR_KEY_PATTERN.matcher(key).matches()) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                            "Invalid attribute key: " + key + ". Must be alphanumeric/hyphen/underscore up to 50 chars.");
                }
                if (val == null || val.trim().isEmpty() || val.length() > 100) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                            "Invalid attribute value for key '" + key + "': must be non-empty and up to 100 characters.");
                }
            }
        }
    }

    private SearchProductResponse searchWithElasticsearch(SearchProductRequest req) {
        NativeQueryBuilder qb = NativeQuery.builder();

        // 1. Bool query building
        if (req.getInStock() != null) {
            // Check if any matching active SKU documents have unknown (null/missing) stock
            co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder unknownStockCheck = buildBaseBoolQuery(req);
            unknownStockCheck.filter(f -> f.bool(b -> b.mustNot(mn -> mn.exists(e -> e.field("inStock")))));

            NativeQuery checkQuery = NativeQuery.builder()
                    .withQuery(unknownStockCheck.build()._toQuery())
                    .withPageable(PageRequest.of(0, 1))
                    .build();

            long unknownCount = elasticsearchOperations.count(checkQuery, ProductSkuDocument.class,
                    IndexCoordinates.of("product_catalog"));
            if (unknownCount > 0) {
                throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,
                        "Inventory stock status is unknown for one or more requested items");
            }
        }

        co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder boolQuery = buildBaseBoolQuery(req);
        if (req.getInStock() != null) {
            boolQuery.filter(f -> f.term(t -> t.field("inStock").value(req.getInStock())));
        }

        qb.withQuery(boolQuery.build()._toQuery());

        // 2. Sorting with deterministic skuId ASC tie-breaker
        Sort sort = buildSort(req.getSort(), req.getQ());
        qb.withSort(sort);

        // 3. Pagination / Cursor
        boolean isCursorMode = "cursor".equalsIgnoreCase(req.getMode());
        String fingerprint = searchCursorCodec.computeFingerprint(req);
        int fetchSize = isCursorMode ? req.getSize() + 1 : req.getSize();

        String pitId = null;
        if (isCursorMode) {
            qb.withPageable(PageRequest.of(0, fetchSize));
            if (req.getCursor() != null && !req.getCursor().trim().isEmpty()) {
                SearchCursorCodec.CursorPayload cursorPayload = searchCursorCodec.decode(req.getCursor(), fingerprint);
                if (cursorPayload != null) {
                    if (cursorPayload.isExpired()) {
                        log.info("Cursor expired; restarting search from the beginning per spec");
                        if (cursorPayload.getPitId() != null) {
                            try {
                                elasticsearchOperations.closePointInTime(cursorPayload.getPitId());
                            } catch (Exception ignored) {}
                        }
                        pitId = elasticsearchOperations.openPointInTime(IndexCoordinates.of("product_catalog"), Duration.ofMinutes(1));
                    } else {
                        pitId = cursorPayload.getPitId();
                        if (cursorPayload.getSortValues() != null) {
                            qb.withSearchAfter(cursorPayload.getSortValues());
                        }
                    }
                }
            } else {
                pitId = elasticsearchOperations.openPointInTime(IndexCoordinates.of("product_catalog"), Duration.ofMinutes(1));
            }

            if (pitId != null) {
                qb.withPointInTime(new Query.PointInTime(pitId, Duration.ofMinutes(1)));
            }
        } else {
            qb.withPageable(PageRequest.of(req.getPage(), req.getSize()));
        }

        SearchHits<ProductSkuDocument> hits;
        try {
            hits = elasticsearchOperations.search(qb.build(), ProductSkuDocument.class,
                    IndexCoordinates.of("product_catalog"));
        } catch (Exception e) {
            if (isCursorMode && req.getCursor() != null && !req.getCursor().trim().isEmpty() &&
                    (e.getMessage() != null && (e.getMessage().toLowerCase().contains("point in time") || e.getMessage().toLowerCase().contains("pit")))) {
                log.warn("Search with PIT {} failed on server: {}. Restarting search from page 1.", pitId, e.getMessage());
                pitId = elasticsearchOperations.openPointInTime(IndexCoordinates.of("product_catalog"), Duration.ofMinutes(1));
                qb = NativeQuery.builder();
                qb.withQuery(boolQuery.build()._toQuery());
                qb.withSort(sort);
                qb.withPageable(PageRequest.of(0, fetchSize));
                if (pitId != null) {
                    qb.withPointInTime(new Query.PointInTime(pitId, Duration.ofMinutes(1)));
                }
                hits = elasticsearchOperations.search(qb.build(), ProductSkuDocument.class,
                        IndexCoordinates.of("product_catalog"));
            } else {
                throw e;
            }
        }

        List<ProductSearchItem> items = new ArrayList<>();

        List<SearchHit<ProductSkuDocument>> hitList = hits.getSearchHits();
        boolean hasMore = isCursorMode && hitList.size() > req.getSize();
        int resultCount = hasMore ? req.getSize() : hitList.size();

        for (int i = 0; i < resultCount; i++) {
            items.add(toSearchItem(hitList.get(i).getContent()));
        }

        String nextCursor = null;
        if (hasMore && resultCount > 0) {
            List<Object> lastSortValues = hitList.get(resultCount - 1).getSortValues();
            nextCursor = searchCursorCodec.encode(pitId, lastSortValues, fingerprint);
        } else if (isCursorMode && pitId != null) {
            try {
                elasticsearchOperations.closePointInTime(pitId);
            } catch (Exception ignored) {}
        }

        return SearchProductResponse.builder()
                .items(items)
                .page(isCursorMode ? null : req.getPage())
                .size(req.getSize())
                .total(hits.getTotalHits())
                .nextCursor(nextCursor)
                .source("elasticsearch")
                .build();
    }

    private Sort buildSort(String sortType, String queryText) {
        String type = sortType != null ? sortType.toLowerCase() : "relevance";
        Sort baseSort;

        switch (type) {
            case "price_asc":
                baseSort = Sort.by(Sort.Direction.ASC, "price");
                break;
            case "price_desc":
                baseSort = Sort.by(Sort.Direction.DESC, "price");
                break;
            case "newest":
                baseSort = Sort.by(Sort.Direction.DESC, "updatedAt");
                break;
            case "relevance":
            default:
                if (queryText != null && !queryText.trim().isEmpty()) {
                    baseSort = Sort.by(Sort.Direction.DESC, "_score");
                } else {
                    baseSort = Sort.by(Sort.Direction.DESC, "updatedAt");
                }
                break;
        }

        // Always append deterministic skuId ASC tie-breaker
        return baseSort.and(Sort.by(Sort.Direction.ASC, "skuId"));
    }

    @Transactional(readOnly = true)
    public SearchProductResponse searchWithPostgresFallback(SearchProductRequest req) {
        if ("cursor".equalsIgnoreCase(req.getMode())) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "Cursor pagination is temporarily unavailable due to search engine fallback. Please use offset mode.");
        }

        if (req.getInStock() != null) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "Stock filtering is temporarily unavailable during search fallback");
        }

        // Fetch ACTIVE products whose Category and Brand are both ACTIVE
        List<Category> activeCategories = categoryRepository.findByStatusOrderByDisplayOrderAscNameAscIdAsc(CategoryStatus.ACTIVE);
        Set<UUID> activeCategoryIds = activeCategories.stream().map(Category::getId).collect(Collectors.toSet());
        Map<UUID, String> categoryNames = activeCategories.stream().collect(Collectors.toMap(Category::getId, Category::getName));

        List<Brand> activeBrands = brandRepository.findByStatusOrderByNameAsc(BrandStatus.ACTIVE);
        Set<UUID> activeBrandIds = activeBrands.stream().map(Brand::getId).collect(Collectors.toSet());
        Map<UUID, String> brandNames = activeBrands.stream().collect(Collectors.toMap(Brand::getId, Brand::getName));

        List<Product> products = productRepository.findByStatusAndDeletedAtIsNull(ProductStatus.ACTIVE).stream()
                .filter(p -> activeCategoryIds.contains(p.getCategoryId()) && activeBrandIds.contains(p.getBrandId()))
                .filter(p -> req.getCategoryId() == null || p.getCategoryId().equals(req.getCategoryId()))
                .filter(p -> req.getBrandId() == null || p.getBrandId().equals(req.getBrandId()))
                .collect(Collectors.toList());

        List<ProductSearchItem> matchedItems = new ArrayList<>();

        for (Product product : products) {
            List<Sku> skus = skuRepository.findByProductIdAndStatusAndDeletedAtIsNull(product.getId(), SkuStatus.ACTIVE);
            List<ProductImage> images = productImageRepository.findByProductIdOrderByDisplayOrderAscCreatedAtAsc(product.getId());
            List<ProductImageResponse> imageResponses = images.stream().map(img -> ProductImageResponse.builder()
                    .id(img.getId())
                    .productId(img.getProductId())
                    .skuId(img.getSkuId())
                    .imageUrl(img.getUrl())
                    .altText(img.getAltText())
                    .primary(img.isPrimary())
                    .displayOrder(img.getDisplayOrder())
                    .createdAt(img.getCreatedAt())
                    .build()).collect(Collectors.toList());

            for (Sku sku : skus) {
                // Apply price filters
                if (req.getMinPrice() != null && sku.getPrice().compareTo(req.getMinPrice()) < 0) {
                    continue;
                }
                if (req.getMaxPrice() != null && sku.getPrice().compareTo(req.getMaxPrice()) > 0) {
                    continue;
                }

                // Apply attributes filter if requested
                if (req.getAttributes() != null && !req.getAttributes().isEmpty()) {
                    Map<String, Object> skuAttrs = parseAttributes(sku.getAttributes());
                    boolean matchesAttrs = true;
                    for (Map.Entry<String, String> entry : req.getAttributes().entrySet()) {
                        Object attrVal = skuAttrs.get(entry.getKey());
                        if (attrVal == null || !String.valueOf(attrVal).equalsIgnoreCase(entry.getValue())) {
                            matchesAttrs = false;
                            break;
                        }
                    }
                    if (!matchesAttrs) {
                        continue;
                    }
                }

                // Apply text filter if q is present
                if (req.getQ() != null && !req.getQ().trim().isEmpty()) {
                    String query = req.getQ().trim().toLowerCase();
                    String catName = categoryNames.get(product.getCategoryId());
                    String bName = brandNames.get(product.getBrandId());
                    boolean matchesText = product.getName().toLowerCase().contains(query) ||
                            (product.getDescription() != null && product.getDescription().toLowerCase().contains(query)) ||
                            sku.getName().toLowerCase().contains(query) ||
                            sku.getSkuCode().toLowerCase().contains(query) ||
                            (catName != null && catName.toLowerCase().contains(query)) ||
                            (bName != null && bName.toLowerCase().contains(query));
                    if (!matchesText) {
                        continue;
                    }
                }

                matchedItems.add(ProductSearchItem.builder()
                        .skuId(sku.getId())
                        .skuCode(sku.getSkuCode())
                        .productId(product.getId())
                        .productName(product.getName())
                        .name(sku.getName())
                        .description(product.getDescription())
                        .categoryId(product.getCategoryId())
                        .categoryName(categoryNames.get(product.getCategoryId()))
                        .brandId(product.getBrandId())
                        .brandName(brandNames.get(product.getBrandId()))
                        .price(sku.getPrice().doubleValue())
                        .currency(sku.getCurrency())
                        .attributes(parseAttributes(sku.getAttributes()))
                        .images(imageResponses)
                        .inStock(null) // UNKNOWN on DB fallback
                        .updatedAt(sku.getUpdatedAt())
                        .build());
            }
        }

        // Apply sorting
        Comparator<ProductSearchItem> comparator;
        String sortType = req.getSort() != null ? req.getSort().toLowerCase() : "newest";
        switch (sortType) {
            case "price_asc":
                comparator = Comparator.comparing(ProductSearchItem::getPrice)
                        .thenComparing(ProductSearchItem::getSkuId);
                break;
            case "price_desc":
                comparator = Comparator.comparing(ProductSearchItem::getPrice, Comparator.reverseOrder())
                        .thenComparing(ProductSearchItem::getSkuId);
                break;
            case "newest":
            default:
                comparator = Comparator.comparing(ProductSearchItem::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(ProductSearchItem::getSkuId);
                break;
        }

        matchedItems.sort(comparator);

        long total = matchedItems.size();
        int fromIndex = Math.min(req.getPage() * req.getSize(), matchedItems.size());
        int toIndex = Math.min(fromIndex + req.getSize(), matchedItems.size());
        List<ProductSearchItem> pagedItems = matchedItems.subList(fromIndex, toIndex);

        return SearchProductResponse.builder()
                .items(pagedItems)
                .page(req.getPage())
                .size(req.getSize())
                .total(total)
                .nextCursor(null)
                .source("postgres-fallback")
                .build();
    }

    private ProductSearchItem toSearchItem(ProductSkuDocument doc) {
        List<ProductImageResponse> images = doc.getImages() != null
                ? doc.getImages().stream().map(img -> ProductImageResponse.builder()
                .imageUrl(img.getUrl())
                .altText(img.getAltText())
                .primary(img.isPrimary())
                .displayOrder(img.getDisplayOrder())
                .build()).collect(Collectors.toList())
                : List.of();

        return ProductSearchItem.builder()
                .skuId(doc.getSkuId() != null ? UUID.fromString(doc.getSkuId()) : null)
                .skuCode(doc.getSkuCode())
                .productId(doc.getProductId() != null ? UUID.fromString(doc.getProductId()) : null)
                .productName(doc.getProductName())
                .name(doc.getName())
                .description(doc.getDescription())
                .categoryId(doc.getCategoryId() != null ? UUID.fromString(doc.getCategoryId()) : null)
                .categoryName(doc.getCategoryName())
                .brandId(doc.getBrandId() != null ? UUID.fromString(doc.getBrandId()) : null)
                .brandName(doc.getBrandName())
                .price(doc.getPrice())
                .currency(doc.getCurrency())
                .attributes(doc.getAttributes())
                .images(images)
                .inStock(doc.getInStock())
                .updatedAt(doc.getUpdatedAt())
                .build();
    }

    private Map<String, Object> parseAttributes(String attributesJson) {
        if (attributesJson == null || attributesJson.trim().isEmpty() || attributesJson.trim().equals("{}")) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(attributesJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder buildBaseBoolQuery(SearchProductRequest req) {
        co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder boolQuery =
                new co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder();

        // Active SKU status filter
        boolQuery.filter(f -> f.term(t -> t.field("status").value("ACTIVE")));

        if (req.getCategoryId() != null) {
            boolQuery.filter(f -> f.term(t -> t.field("categoryId").value(req.getCategoryId().toString())));
        }

        if (req.getBrandId() != null) {
            boolQuery.filter(f -> f.term(t -> t.field("brandId").value(req.getBrandId().toString())));
        }

        if (req.getMinPrice() != null || req.getMaxPrice() != null) {
            boolQuery.filter(f -> f.range(r -> r.number(n -> {
                n.field("price");
                if (req.getMinPrice() != null) {
                    n.gte(req.getMinPrice().doubleValue());
                }
                if (req.getMaxPrice() != null) {
                    n.lte(req.getMaxPrice().doubleValue());
                }
                return n;
            })));
        }

        if (req.getAttributes() != null && !req.getAttributes().isEmpty()) {
            for (Map.Entry<String, String> entry : req.getAttributes().entrySet()) {
                boolQuery.filter(f -> f.term(t -> t.field("attributes." + entry.getKey()).value(entry.getValue())));
            }
        }

        if (req.getQ() != null && !req.getQ().trim().isEmpty()) {
            String queryText = req.getQ().trim();
            boolQuery.must(m -> m.multiMatch(mm -> mm
                    .query(queryText)
                    .fields("name^3", "productName^3", "description", "skuCode^2", "categoryName", "brandName")
            ));
        }

        return boolQuery;
    }
}
