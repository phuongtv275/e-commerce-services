package com.example.catalogservice.controller;

import com.ecommerce.common.response.ApiResponse;
import com.example.catalogservice.entity.CatalogAuditLog;
import com.example.catalogservice.repository.CatalogAuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/catalog/admin/audit-logs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMINISTRATOR')")
public class CatalogAuditLogController {

    private final CatalogAuditLogRepository auditLogRepository;

    @GetMapping
    public ResponseEntity<ApiResponse<Page<CatalogAuditLog>>> getAuditLogs(
            @PageableDefault(size = 20, sort = "occurredAt", direction = Sort.Direction.DESC) Pageable pageable) {
        Page<CatalogAuditLog> logs = auditLogRepository.findAll(pageable);
        return ResponseEntity.ok(ApiResponse.success("Get audit logs successfully", logs));
    }
}
