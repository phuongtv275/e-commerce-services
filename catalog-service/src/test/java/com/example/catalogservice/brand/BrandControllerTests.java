package com.example.catalogservice.brand;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.exception.ConflictException;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.example.catalogservice.dto.req.CreateBrandRequest;
import com.example.catalogservice.dto.req.UpdateBrandRequest;
import com.example.catalogservice.dto.req.UpdateStatusRequest;
import com.example.catalogservice.dto.resp.BrandResponse;
import com.example.catalogservice.entity.BrandStatus;
import com.example.catalogservice.service.BrandService;
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
import static org.mockito.Mockito.*;
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
class BrandControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private BrandService brandService;

    @Test
    @DisplayName("Positive: Public GET /api/v1/catalog/brands returns active brands without authentication")
    void testGetPublicBrands() throws Exception {
        BrandResponse brand = BrandResponse.builder()
                .id(UUID.randomUUID())
                .name("Sony")
                .slug("sony")
                .status(BrandStatus.ACTIVE)
                .build();
        when(brandService.getPublicBrands()).thenReturn(List.of(brand));

        mockMvc.perform(get("/api/v1/catalog/brands"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].name").value("Sony"));
    }

    @Test
    @DisplayName("Positive: Public GET /api/v1/catalog/brands/{id} returns brand")
    void testGetPublicBrandById() throws Exception {
        UUID id = UUID.randomUUID();
        BrandResponse response = BrandResponse.builder()
                .id(id)
                .name("Apple")
                .slug("apple")
                .status(BrandStatus.ACTIVE)
                .build();
        when(brandService.getBrandById(id)).thenReturn(response);

        mockMvc.perform(get("/api/v1/catalog/brands/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.slug").value("apple"));
    }

    @Test
    @DisplayName("Positive: POST /api/v1/catalog/admin/brands creates brand with ROLE_CATALOG_STAFF")
    void testCreateBrand_StaffAllowed() throws Exception {
        CreateBrandRequest req = CreateBrandRequest.builder()
                .name("Samsung")
                .slug("samsung")
                .description("South Korean electronics brand")
                .logoUrl("https://example.com/logo.png")
                .build();

        BrandResponse res = BrandResponse.builder()
                .id(UUID.randomUUID())
                .name("Samsung")
                .slug("samsung")
                .status(BrandStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();

        when(brandService.createBrand(any(), eq("staff-user"))).thenReturn(res);

        mockMvc.perform(post("/api/v1/catalog/admin/brands")
                        .with(user("staff-user").roles("CATALOG_STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.name").value("Samsung"));
    }

    @Test
    @DisplayName("Positive: DELETE /api/v1/catalog/admin/brands/{id} succeeds with ROLE_ADMINISTRATOR")
    void testDeleteBrand_AdminAllowed() throws Exception {
        UUID id = UUID.randomUUID();
        doNothing().when(brandService).deleteBrand(eq(id), eq("admin-user"));

        mockMvc.perform(delete("/api/v1/catalog/admin/brands/{id}", id)
                        .with(user("admin-user").roles("ADMINISTRATOR")))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Negative: POST /api/v1/catalog/admin/brands without auth returns 401")
    void testCreateBrand_Unauthenticated() throws Exception {
        CreateBrandRequest req = CreateBrandRequest.builder()
                .name("Samsung")
                .slug("samsung")
                .build();

        mockMvc.perform(post("/api/v1/catalog/admin/brands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_REQUIRED.name()));
    }

    @Test
    @DisplayName("Negative: POST /api/v1/catalog/admin/brands with ROLE_CUSTOMER returns 403")
    void testCreateBrand_CustomerForbidden() throws Exception {
        CreateBrandRequest req = CreateBrandRequest.builder()
                .name("Samsung")
                .slug("samsung")
                .build();

        mockMvc.perform(post("/api/v1/catalog/admin/brands")
                        .with(user("customer-user").roles("CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.name()));
    }

    @Test
    @DisplayName("Negative: DELETE /api/v1/catalog/admin/brands/{id} with ROLE_CATALOG_STAFF returns 403")
    void testDeleteBrand_StaffForbidden() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/catalog/admin/brands/{id}", id)
                        .with(user("staff-user").roles("CATALOG_STAFF")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.name()));
    }

    @Test
    @DisplayName("Negative: POST /api/v1/catalog/admin/brands with blank name returns 400")
    void testCreateBrand_ValidationFailure() throws Exception {
        CreateBrandRequest req = CreateBrandRequest.builder()
                .name("")
                .slug("samsung")
                .build();

        mockMvc.perform(post("/api/v1/catalog/admin/brands")
                        .with(user("staff-user").roles("CATALOG_STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.name()));
    }

    @Test
    @DisplayName("Negative: POST /api/v1/catalog/admin/brands with duplicate slug returns 409")
    void testCreateBrand_DuplicateSlug() throws Exception {
        CreateBrandRequest req = CreateBrandRequest.builder()
                .name("Samsung")
                .slug("samsung")
                .build();

        when(brandService.createBrand(any(), eq("staff-user")))
                .thenThrow(new ConflictException("Brand slug already exists: samsung"));

        mockMvc.perform(post("/api/v1/catalog/admin/brands")
                        .with(user("staff-user").roles("CATALOG_STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.STATE_CONFLICT.name()));
    }

    @Test
    @DisplayName("Negative: GET /api/v1/catalog/brands/{id} for non-existent returns 404")
    void testGetBrand_NotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(brandService.getBrandById(id))
                .thenThrow(new ResourceNotFoundException("Brand not found with id: " + id));

        mockMvc.perform(get("/api/v1/catalog/brands/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.RESOURCE_NOT_FOUND.name()));
    }
}
