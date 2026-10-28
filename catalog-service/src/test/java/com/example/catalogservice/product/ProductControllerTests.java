package com.example.catalogservice.product;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.example.catalogservice.dto.req.CreateProductRequest;
import com.example.catalogservice.dto.req.UpdateProductRequest;
import com.example.catalogservice.dto.resp.ProductResponse;
import com.example.catalogservice.entity.ProductStatus;
import com.example.catalogservice.service.ProductService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
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
class ProductControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ProductService productService;

    @Test
    @DisplayName("Positive: POST /api/v1/catalog/admin/products with ROLE_CATALOG_STAFF returns 201")
    void testCreateProduct_StaffAllowed() throws Exception {
        UUID categoryId = UUID.randomUUID();
        UUID brandId = UUID.randomUUID();

        CreateProductRequest req = CreateProductRequest.builder()
                .name("MacBook Pro")
                .categoryId(categoryId)
                .brandId(brandId)
                .build();

        ProductResponse response = ProductResponse.builder()
                .id(UUID.randomUUID())
                .name("MacBook Pro")
                .categoryId(categoryId)
                .brandId(brandId)
                .status(ProductStatus.DRAFT)
                .createdAt(Instant.now())
                .build();

        when(productService.createProduct(any(), eq("staff-user"))).thenReturn(response);

        mockMvc.perform(post("/api/v1/catalog/admin/products")
                        .with(user("staff-user").roles("CATALOG_STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.name").value("MacBook Pro"));
    }

    @Test
    @DisplayName("Negative: POST /api/v1/catalog/admin/products without auth returns 401")
    void testCreateProduct_Unauthenticated() throws Exception {
        CreateProductRequest req = CreateProductRequest.builder()
                .name("MacBook Pro")
                .categoryId(UUID.randomUUID())
                .brandId(UUID.randomUUID())
                .build();

        mockMvc.perform(post("/api/v1/catalog/admin/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_REQUIRED.name()));
    }

    @Test
    @DisplayName("Negative: POST /api/v1/catalog/admin/products with ROLE_CUSTOMER returns 403")
    void testCreateProduct_CustomerForbidden() throws Exception {
        CreateProductRequest req = CreateProductRequest.builder()
                .name("MacBook Pro")
                .categoryId(UUID.randomUUID())
                .brandId(UUID.randomUUID())
                .build();

        mockMvc.perform(post("/api/v1/catalog/admin/products")
                        .with(user("customer-user").roles("CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.name()));
    }

    @Test
    @DisplayName("Negative: POST /api/v1/catalog/admin/products with blank name returns 400")
    void testCreateProduct_ValidationFailure() throws Exception {
        CreateProductRequest req = CreateProductRequest.builder()
                .name("")
                .categoryId(UUID.randomUUID())
                .brandId(UUID.randomUUID())
                .build();

        mockMvc.perform(post("/api/v1/catalog/admin/products")
                        .with(user("staff-user").roles("CATALOG_STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.name()));
    }

    @Test
    @DisplayName("Negative: GET /api/v1/catalog/admin/products/{id} when not found returns 404")
    void testGetProduct_NotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(productService.getProductById(id)).thenThrow(new ResourceNotFoundException("Product not found with id: " + id));

        mockMvc.perform(get("/api/v1/catalog/admin/products/{id}", id)
                        .with(user("staff-user").roles("CATALOG_STAFF")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.RESOURCE_NOT_FOUND.name()));
    }

    @Test
    @DisplayName("Positive: GET /api/v1/catalog/admin/products with page & size returns 200")
    void testGetAllAdminProducts_WithPagination_Success() throws Exception {
        ProductResponse product = ProductResponse.builder()
                .id(UUID.randomUUID())
                .name("Admin Product 1")
                .status(ProductStatus.ACTIVE)
                .build();

        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(1, 5);
        when(productService.getAllProducts(pageable)).thenReturn(List.of(product));

        mockMvc.perform(get("/api/v1/catalog/admin/products?page=1&size=5")
                        .with(user("staff-user").roles("CATALOG_STAFF")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].name").value("Admin Product 1"));
    }

    @Test
    @DisplayName("Positive: GET /api/v1/catalog/admin/products with default params returns 200")
    void testGetAllAdminProducts_DefaultPagination_Success() throws Exception {
        org.springframework.data.domain.Pageable defaultPageable = org.springframework.data.domain.PageRequest.of(0, 20);
        when(productService.getAllProducts(defaultPageable)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/catalog/admin/products")
                        .with(user("admin-user").roles("ADMINISTRATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    @Test
    @DisplayName("Negative: GET /api/v1/catalog/admin/products without auth returns 401")
    void testGetAllAdminProducts_Unauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/admin/products"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_REQUIRED.name()));
    }

    @Test
    @DisplayName("Negative: GET /api/v1/catalog/admin/products with ROLE_CUSTOMER returns 403")
    void testGetAllAdminProducts_CustomerForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/admin/products")
                        .with(user("customer-user").roles("CUSTOMER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.name()));
    }
}
