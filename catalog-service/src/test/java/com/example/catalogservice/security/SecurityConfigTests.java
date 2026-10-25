package com.example.catalogservice.security;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.constant.SecurityConstants;
import com.ecommerce.common.response.ApiResponse;
import com.example.catalogservice.dto.resp.BrandResponse;
import com.example.catalogservice.dto.resp.CategoryResponse;
import com.example.catalogservice.dto.resp.InternalSkuResponse;
import com.example.catalogservice.service.BrandService;
import com.example.catalogservice.service.CategoryService;
import com.example.catalogservice.service.SkuService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class SecurityConfigTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CategoryService categoryService;

    @MockitoBean
    private BrandService brandService;

    @MockitoBean
    private SkuService skuService;

    @MockitoBean
    private com.example.catalogservice.service.ProductService productService;

    @MockitoBean
    private com.example.catalogservice.elasticsearch.service.ProductSearchService productSearchService;

    @MockitoBean
    private com.example.catalogservice.elasticsearch.service.CatalogReindexService catalogReindexService;

    // --- Positive tests: Public endpoints accessible anonymously ---

    @Test
    @DisplayName("Positive: Public GET /categories is permitted for anonymous users")
    void testPublicGetCategories_AnonymousAllowed() throws Exception {
        when(categoryService.getPublicCategoryTree()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/catalog/categories"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Positive: Public GET /brands is permitted for anonymous users")
    void testPublicGetBrands_AnonymousAllowed() throws Exception {
        when(brandService.getPublicBrands()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/catalog/brands"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Positive: Public GET /products/{id} is permitted for anonymous users")
    void testPublicGetProduct_AnonymousAllowed() throws Exception {
        UUID id = UUID.randomUUID();
        when(productService.getPublicProductDetail(id)).thenReturn(
                com.example.catalogservice.dto.resp.PublicProductDetailResponse.builder()
                        .id(id)
                        .name("MacBook Pro")
                        .build());

        mockMvc.perform(get("/api/v1/catalog/products/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.name()));
    }

    @Test
    @DisplayName("Positive: Public GET /search is permitted for anonymous users")
    void testPublicSearch_AnonymousAllowed() throws Exception {
        when(productSearchService.search(any())).thenReturn(
                com.example.catalogservice.dto.resp.SearchProductResponse.builder()
                        .source("elasticsearch")
                        .build());

        mockMvc.perform(get("/api/v1/catalog/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.name()));
    }

    // --- Admin endpoint security tests ---

    @Test
    @DisplayName("Negative: Anonymous access to admin endpoint returns 401 AUTHENTICATION_REQUIRED")
    void testAdminEndpoint_Anonymous_Returns401() throws Exception {
        mockMvc.perform(post("/api/v1/catalog/admin/categories"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_REQUIRED.name()));
    }

    @Test
    @DisplayName("Negative: Customer access to admin endpoint returns 403 ACCESS_DENIED")
    void testAdminEndpoint_CustomerRole_Returns403() throws Exception {
        mockMvc.perform(post("/api/v1/catalog/admin/categories")
                        .with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.name()));
    }

    @Test
    @DisplayName("Positive: Staff access to admin category endpoint returns 201 Created")
    void testAdminEndpoint_CatalogStaff_Allowed() throws Exception {
        when(categoryService.createCategory(any(), any())).thenReturn(CategoryResponse.builder().name("Test").build());

        mockMvc.perform(post("/api/v1/catalog/admin/categories")
                        .with(user("staff").roles("CATALOG_STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test\",\"slug\":\"test\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Negative: Staff access to admin reindex endpoint returns 403 (administrator only)")
    void testAdminReindex_Staff_Returns403() throws Exception {
        mockMvc.perform(post("/api/v1/catalog/admin/reindex")
                        .with(user("staff").roles("CATALOG_STAFF")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.name()));
    }

    @Test
    @DisplayName("Positive: Administrator access to admin reindex endpoint returns 202 Accepted")
    void testAdminReindex_Admin_Allowed() throws Exception {
        when(catalogReindexService.createReindexJob(any())).thenReturn(
                com.example.catalogservice.dto.resp.ReindexJobResponse.builder()
                        .id(UUID.randomUUID())
                        .status(com.example.catalogservice.entity.ReindexJobStatus.QUEUED)
                        .build());

        mockMvc.perform(post("/api/v1/catalog/admin/reindex")
                        .with(user("admin").roles("ADMINISTRATOR")))
                .andExpect(status().isAccepted());
    }

    // --- Internal endpoint security tests ---

    @Test
    @DisplayName("Negative: Internal endpoint without auth headers returns 401 AUTHENTICATION_REQUIRED")
    void testInternalEndpoint_MissingHeaders_Returns401() throws Exception {
        mockMvc.perform(get("/internal/catalog/skus/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_REQUIRED.name()));
    }

    @Test
    @DisplayName("Negative: Internal endpoint with invalid service key returns 403 ACCESS_DENIED")
    void testInternalEndpoint_InvalidKey_Returns403() throws Exception {
        mockMvc.perform(get("/internal/catalog/skus/" + UUID.randomUUID())
                        .header(SecurityConstants.HEADER_CALLING_SERVICE, "order-service")
                        .header(SecurityConstants.HEADER_INTERNAL_SERVICE_KEY, "wrong-key"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.name()));
    }

    @Test
    @DisplayName("Negative: Internal endpoint rejects Gateway Assertion header with 403")
    void testInternalEndpoint_GatewayAssertionPassed_Returns403() throws Exception {
        mockMvc.perform(get("/internal/catalog/skus/" + UUID.randomUUID())
                        .header(SecurityConstants.HEADER_GATEWAY_ASSERTION, "some-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.name()));
    }

    @Test
    @DisplayName("Positive: Internal endpoint with valid order-service key is permitted")
    void testInternalEndpoint_ValidOrderServiceKey_Allowed() throws Exception {
        UUID skuId = UUID.randomUUID();
        when(skuService.getInternalSku(skuId)).thenReturn(InternalSkuResponse.builder().skuId(skuId).build());

        mockMvc.perform(get("/internal/catalog/skus/" + skuId)
                        .header(SecurityConstants.HEADER_CALLING_SERVICE, "order-service")
                        .header(SecurityConstants.HEADER_INTERNAL_SERVICE_KEY, "order-service-secret-key-32chars"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Negative: Internal endpoint rejects other service callers with 403 (requires SERVICE_ORDER)")
    void testInternalEndpoint_OtherServiceCaller_Returns403() throws Exception {
        mockMvc.perform(get("/internal/catalog/skus/" + UUID.randomUUID())
                        .header(SecurityConstants.HEADER_CALLING_SERVICE, "notification-service")
                        .header(SecurityConstants.HEADER_INTERNAL_SERVICE_KEY, "notification-service-secret-key-32c"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.name()));
    }
}
