package com.example.catalogservice.reindex;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.example.catalogservice.dto.resp.ReindexJobResponse;
import com.example.catalogservice.elasticsearch.service.CatalogReindexService;
import com.example.catalogservice.entity.ReindexJobStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
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
class ReindexAdminControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CatalogReindexService catalogReindexService;

    @Test
    @DisplayName("Positive: Administrator can trigger reindex job and gets 202 Accepted")
    void testCreateReindexJob_Administrator_Allowed() throws Exception {
        UUID jobId = UUID.randomUUID();
        ReindexJobResponse resp = ReindexJobResponse.builder()
                .id(jobId)
                .status(ReindexJobStatus.QUEUED)
                .targetIndex("product_catalog-v1")
                .totalDocuments(50L)
                .processedDocuments(0L)
                .createdAt(Instant.now())
                .build();

        when(catalogReindexService.createReindexJob(any())).thenReturn(resp);

        mockMvc.perform(post("/api/v1/catalog/admin/reindex")
                        .with(user("admin-user").roles("ADMINISTRATOR")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.name()))
                .andExpect(jsonPath("$.data.id").value(jobId.toString()))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
    }

    @Test
    @DisplayName("Negative: Anonymous access to POST /admin/reindex returns 401")
    void testCreateReindexJob_Anonymous_Returns401() throws Exception {
        mockMvc.perform(post("/api/v1/catalog/admin/reindex"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_REQUIRED.name()));
    }

    @Test
    @DisplayName("Negative: CATALOG_STAFF access to POST /admin/reindex returns 403")
    void testCreateReindexJob_Staff_Returns403() throws Exception {
        mockMvc.perform(post("/api/v1/catalog/admin/reindex")
                        .with(user("staff-user").roles("CATALOG_STAFF")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.name()));
    }

    @Test
    @DisplayName("Negative: CUSTOMER access to POST /admin/reindex returns 403")
    void testCreateReindexJob_Customer_Returns403() throws Exception {
        mockMvc.perform(post("/api/v1/catalog/admin/reindex")
                        .with(user("cust-user").roles("CUSTOMER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.name()));
    }

    @Test
    @DisplayName("Positive: Administrator can fetch reindex job status")
    void testGetJobStatus_Administrator_Allowed() throws Exception {
        UUID jobId = UUID.randomUUID();
        ReindexJobResponse resp = ReindexJobResponse.builder()
                .id(jobId)
                .status(ReindexJobStatus.COMPLETED)
                .targetIndex("product_catalog-v2")
                .totalDocuments(100L)
                .processedDocuments(100L)
                .createdAt(Instant.now())
                .finishedAt(Instant.now())
                .build();

        when(catalogReindexService.getJobStatus(jobId)).thenReturn(resp);

        mockMvc.perform(get("/api/v1/catalog/admin/reindex/{jobId}", jobId)
                        .with(user("admin-user").roles("ADMINISTRATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.name()))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.processedDocuments").value(100));
    }

    @Test
    @DisplayName("Negative: CATALOG_STAFF access to GET /admin/reindex/{jobId} returns 403")
    void testGetJobStatus_Staff_Returns403() throws Exception {
        UUID jobId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/catalog/admin/reindex/{jobId}", jobId)
                        .with(user("staff-user").roles("CATALOG_STAFF")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.name()));
    }

    @Test
    @DisplayName("Negative: Non-existent jobId returns 404 RESOURCE_NOT_FOUND")
    void testGetJobStatus_NotFound() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(catalogReindexService.getJobStatus(jobId))
                .thenThrow(new ResourceNotFoundException("Reindex job not found with id: " + jobId));

        mockMvc.perform(get("/api/v1/catalog/admin/reindex/{jobId}", jobId)
                        .with(user("admin-user").roles("ADMINISTRATOR")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.RESOURCE_NOT_FOUND.name()));
    }
}
