package com.example.catalogservice.controller;

import com.ecommerce.common.constant.ErrorCode;
import com.example.catalogservice.entity.CatalogAuditLog;
import com.example.catalogservice.repository.CatalogAuditLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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
class CatalogAuditLogControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CatalogAuditLogRepository auditLogRepository;

    @Test
    @DisplayName("Positive: Administrator can fetch audit logs with pagination")
    void testGetAuditLogs_Administrator_Success() throws Exception {
        CatalogAuditLog logEntry = CatalogAuditLog.builder()
                .id(UUID.randomUUID())
                .actorId("admin-1")
                .actorType("USER")
                .action("CREATE_PRODUCT")
                .entityType("PRODUCT")
                .entityId(UUID.randomUUID().toString())
                .occurredAt(Instant.now())
                .build();

        Page<CatalogAuditLog> page = new PageImpl<>(List.of(logEntry));
        when(auditLogRepository.findAll(any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/v1/catalog/admin/audit-logs")
                        .with(user("admin-user").roles("ADMINISTRATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.name()))
                .andExpect(jsonPath("$.data.content[0].action").value("CREATE_PRODUCT"))
                .andExpect(jsonPath("$.data.content[0].actorId").value("admin-1"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @DisplayName("Negative: Anonymous request to GET /api/v1/catalog/admin/audit-logs returns 401")
    void testGetAuditLogs_Anonymous_Returns401() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/admin/audit-logs"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_REQUIRED.name()));
    }

    @Test
    @DisplayName("Negative: CATALOG_STAFF access to GET /api/v1/catalog/admin/audit-logs returns 403")
    void testGetAuditLogs_Staff_Returns403() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/admin/audit-logs")
                        .with(user("staff-user").roles("CATALOG_STAFF")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.name()));
    }

    @Test
    @DisplayName("Negative: CUSTOMER access to GET /api/v1/catalog/admin/audit-logs returns 403")
    void testGetAuditLogs_Customer_Returns403() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/admin/audit-logs")
                        .with(user("cust-user").roles("CUSTOMER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.name()));
    }
}
