package com.example.identityservice.controllers;

import com.ecommerce.common.response.ApiResponse;
import com.example.identityservice.models.constants.UserStatus;
import com.example.identityservice.models.dto.req.AssignRoleReq;
import com.example.identityservice.models.dto.res.AuditLogRes;
import com.example.identityservice.models.dto.res.UserSummaryRes;
import com.example.identityservice.models.services.AdminService;
import com.example.identityservice.models.services.AuditLogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMINISTRATOR')")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final AuditLogService auditLogService;

    @GetMapping("/users")
    public ResponseEntity<ApiResponse<Page<UserSummaryRes>>> getUsers(
            @RequestParam(required = false) String email,
            @RequestParam(required = false) UserStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<UserSummaryRes> page = adminService.getUsers(email, status, pageable);
        return ResponseEntity.ok(ApiResponse.success("Get users successfully", page));
    }

    @PostMapping("/users/{userId}/roles")
    public ResponseEntity<ApiResponse<Void>> assignRole(
            @PathVariable UUID userId,
            @RequestBody @Valid AssignRoleReq req) {
        adminService.assignRole(userId, req);
        return ResponseEntity.ok(ApiResponse.success("Assign role successfully", null));
    }

    @DeleteMapping("/users/{userId}/roles/{roleName}")
    public ResponseEntity<ApiResponse<Void>> revokeRole(
            @PathVariable UUID userId,
            @PathVariable String roleName) {
        adminService.revokeRole(userId, roleName);
        return ResponseEntity.ok(ApiResponse.success("Revoke role successfully", null));
    }

    @GetMapping("/audit-logs")
    public ResponseEntity<ApiResponse<Page<AuditLogRes>>> getAuditLogs(
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<AuditLogRes> logs = auditLogService.getAuditLogs(actorId, action, from, to, pageable);
        return ResponseEntity.ok(ApiResponse.success("Get audit logs successfully", logs));
    }
}
