package com.example.catalogservice.search;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.example.catalogservice.dto.req.SearchProductRequest;
import com.example.catalogservice.dto.resp.ProductSearchItem;
import com.example.catalogservice.dto.resp.PublicProductDetailResponse;
import com.example.catalogservice.dto.resp.SearchProductResponse;
import com.example.catalogservice.dto.resp.SkuResponse;
import com.example.catalogservice.elasticsearch.service.ProductSearchService;
import com.example.catalogservice.entity.SkuStatus;
import com.example.catalogservice.service.ProductService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "management.health.elasticsearch.enabled=false",
        "security.internal-service-keys.order-service=order-service-secret-key-32chars",
        "security.internal-service-keys.notification-service=notification-service-secret-key-32c",
        "security.gateway-assertion.public-key=MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAr+qpeFC7N9yYQYKyKYd3wWHnhth0aithJSysJifsaz1k3n0QKMOejmJxK8BrGwYGSH7tCjlRkOUPG5h4jWgGuv3CgnCJR8hzSx362wYkcykI4e0Umjya3ebd2ArPKN0NgZ0xfIphtsS+OQCKFOF5FcZr/T14qP62Dtr+8K91VltTiNbOTxrGxBLfhxf9CZ9iSYZy3xnQW2lwL+wblp2ps8qOBLvCp8QP81Ffa7M8IM6qTX24g5FnmCub/jPJ/9oqJNFvRw5hRHQvNLvY72P9a+nN4Jg4N36A2djbAR9Ws4kWkB4Inrbwg/kVrxWafrPoXCZG+aPcF3pbJ1Sz333EFwIDAQAB"
})
class PublicProductControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private ProductSearchService productSearchService;

    @MockitoBean
    private com.example.catalogservice.elasticsearch.repository.ProductSkuRepository productSkuRepository;

    @MockitoBean
    private org.springframework.data.elasticsearch.core.ElasticsearchOperations elasticsearchOperations;

    @MockitoBean
    private com.example.catalogservice.elasticsearch.service.ElasticsearchIndexService elasticsearchIndexService;

    @Test
    @DisplayName("Positive: Anonymous user can fetch public product detail")
    void testGetProductDetail_Success() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();

        PublicProductDetailResponse detail = PublicProductDetailResponse.builder()
                .id(productId)
                .name("MacBook Pro 16")
                .description("M3 Max power")
                .categoryId(UUID.randomUUID())
                .categoryName("Laptops")
                .brandId(UUID.randomUUID())
                .brandName("Apple")
                .skus(List.of(SkuResponse.builder()
                        .id(skuId)
                        .productId(productId)
                        .skuCode("MBP16-M3MAX")
                        .name("16-inch 36GB")
                        .price(new BigDecimal("75000000"))
                        .currency("VND")
                        .status(SkuStatus.ACTIVE)
                        .build()))
                .images(List.of())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(productService.getPublicProductDetail(productId)).thenReturn(detail);

        mockMvc.perform(get("/api/v1/catalog/products/{id}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.name()))
                .andExpect(jsonPath("$.data.id").value(productId.toString()))
                .andExpect(jsonPath("$.data.name").value("MacBook Pro 16"))
                .andExpect(jsonPath("$.data.skus[0].skuCode").value("MBP16-M3MAX"));
    }

    @Test
    @DisplayName("Negative: Non-existent or inactive product returns 404 RESOURCE_NOT_FOUND")
    void testGetProductDetail_NotFound() throws Exception {
        UUID productId = UUID.randomUUID();
        when(productService.getPublicProductDetail(productId))
                .thenThrow(new ResourceNotFoundException("Product not found with id: " + productId));

        mockMvc.perform(get("/api/v1/catalog/products/{id}", productId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.RESOURCE_NOT_FOUND.name()));
    }

    @Test
    @DisplayName("Positive: Anonymous user can search products")
    void testSearchProducts_Success() throws Exception {
        ProductSearchItem item = ProductSearchItem.builder()
                .skuId(UUID.randomUUID())
                .skuCode("SKU-TEST")
                .name("Test Product")
                .price(100000.0)
                .currency("VND")
                .build();

        SearchProductResponse response = SearchProductResponse.builder()
                .items(List.of(item))
                .page(0)
                .size(20)
                .total(1L)
                .source("elasticsearch")
                .build();

        when(productSearchService.search(any(SearchProductRequest.class))).thenReturn(response);

        mockMvc.perform(get("/api/v1/catalog/search")
                        .param("q", "test")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.name()))
                .andExpect(jsonPath("$.data.source").value("elasticsearch"))
                .andExpect(jsonPath("$.data.items[0].skuCode").value("SKU-TEST"));
    }

    @Test
    @DisplayName("Negative: Search with size > 100 returns 400 VALIDATION_ERROR")
    void testSearchProducts_SizeExceedsMax_ReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/search")
                        .param("size", "150"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.name()));
    }
}
