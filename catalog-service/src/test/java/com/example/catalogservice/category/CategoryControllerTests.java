package com.example.catalogservice.category;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.exception.ConflictException;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.example.catalogservice.dto.req.CreateCategoryRequest;
import com.example.catalogservice.dto.req.UpdateCategoryRequest;
import com.example.catalogservice.dto.req.UpdateStatusRequest;
import com.example.catalogservice.dto.resp.CategoryResponse;
import com.example.catalogservice.dto.resp.CategoryTreeNode;
import com.example.catalogservice.entity.CategoryStatus;
import com.example.catalogservice.service.CategoryService;
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
class CategoryControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CategoryService categoryService;

    @Test
    @DisplayName("Positive: Public GET /api/v1/catalog/categories returns category tree without authentication")
    void testGetPublicCategoryTree() throws Exception {
        CategoryTreeNode node = CategoryTreeNode.builder()
                .id(UUID.randomUUID())
                .name("Electronics")
                .slug("electronics")
                .children(List.of())
                .build();
        when(categoryService.getPublicCategoryTree()).thenReturn(List.of(node));

        mockMvc.perform(get("/api/v1/catalog/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].name").value("Electronics"));
    }

    @Test
    @DisplayName("Positive: Public GET /api/v1/catalog/categories/{id} returns category")
    void testGetPublicCategoryById() throws Exception {
        UUID id = UUID.randomUUID();
        CategoryResponse response = CategoryResponse.builder()
                .id(id)
                .name("Smartphones")
                .slug("smartphones")
                .status(CategoryStatus.ACTIVE)
                .build();
        when(categoryService.getCategoryById(id)).thenReturn(response);

        mockMvc.perform(get("/api/v1/catalog/categories/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.slug").value("smartphones"));
    }

    @Test
    @DisplayName("Positive: POST /api/v1/catalog/admin/categories creates category with ROLE_CATALOG_STAFF")
    void testCreateCategory_StaffAllowed() throws Exception {
        CreateCategoryRequest req = CreateCategoryRequest.builder()
                .name("Laptops")
                .slug("laptops")
                .description("Portable computers")
                .displayOrder(1)
                .build();

        CategoryResponse res = CategoryResponse.builder()
                .id(UUID.randomUUID())
                .name("Laptops")
                .slug("laptops")
                .status(CategoryStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();

        when(categoryService.createCategory(any(), eq("staff-user"))).thenReturn(res);

        mockMvc.perform(post("/api/v1/catalog/admin/categories")
                        .with(user("staff-user").roles("CATALOG_STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.name").value("Laptops"));
    }

    @Test
    @DisplayName("Positive: DELETE /api/v1/catalog/admin/categories/{id} succeeds with ROLE_ADMINISTRATOR")
    void testDeleteCategory_AdminAllowed() throws Exception {
        UUID id = UUID.randomUUID();
        doNothing().when(categoryService).deleteCategory(eq(id), eq("admin-user"));

        mockMvc.perform(delete("/api/v1/catalog/admin/categories/{id}", id)
                        .with(user("admin-user").roles("ADMINISTRATOR")))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Negative: POST /api/v1/catalog/admin/categories without auth returns 401")
    void testCreateCategory_Unauthenticated() throws Exception {
        CreateCategoryRequest req = CreateCategoryRequest.builder()
                .name("Laptops")
                .slug("laptops")
                .build();

        mockMvc.perform(post("/api/v1/catalog/admin/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_REQUIRED.name()));
    }

    @Test
    @DisplayName("Negative: POST /api/v1/catalog/admin/categories with ROLE_CUSTOMER returns 403")
    void testCreateCategory_CustomerForbidden() throws Exception {
        CreateCategoryRequest req = CreateCategoryRequest.builder()
                .name("Laptops")
                .slug("laptops")
                .build();

        mockMvc.perform(post("/api/v1/catalog/admin/categories")
                        .with(user("customer-user").roles("CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.name()));
    }

    @Test
    @DisplayName("Negative: DELETE /api/v1/catalog/admin/categories/{id} with ROLE_CATALOG_STAFF returns 403")
    void testDeleteCategory_StaffForbidden() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/catalog/admin/categories/{id}", id)
                        .with(user("staff-user").roles("CATALOG_STAFF")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.name()));
    }

    @Test
    @DisplayName("Negative: POST /api/v1/catalog/admin/categories with blank name returns 400")
    void testCreateCategory_ValidationFailure() throws Exception {
        CreateCategoryRequest req = CreateCategoryRequest.builder()
                .name("")
                .slug("valid-slug")
                .build();

        mockMvc.perform(post("/api/v1/catalog/admin/categories")
                        .with(user("staff-user").roles("CATALOG_STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.name()));
    }

    @Test
    @DisplayName("Negative: GET /api/v1/catalog/categories/{id} for non-existent returns 404")
    void testGetCategory_NotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(categoryService.getCategoryById(id))
                .thenThrow(new ResourceNotFoundException("Category not found with id: " + id));

        mockMvc.perform(get("/api/v1/catalog/categories/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.RESOURCE_NOT_FOUND.name()));
    }

    @Test
    @DisplayName("Negative: PUT /api/v1/catalog/admin/categories/{id} causing cycle returns 409")
    void testUpdateCategory_CycleConflict() throws Exception {
        UUID id = UUID.randomUUID();
        UpdateCategoryRequest req = UpdateCategoryRequest.builder()
                .name("A")
                .slug("a")
                .parentId(UUID.randomUUID())
                .build();

        when(categoryService.updateCategory(eq(id), any(), eq("staff-user")))
                .thenThrow(new ConflictException("Reparenting would introduce a cycle in category tree"));

        mockMvc.perform(put("/api/v1/catalog/admin/categories/{id}", id)
                        .with(user("staff-user").roles("CATALOG_STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.STATE_CONFLICT.name()));
    }
}
