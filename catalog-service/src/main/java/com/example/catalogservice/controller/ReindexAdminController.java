package com.example.catalogservice.controller;

import com.ecommerce.common.response.ApiResponse;
import com.example.catalogservice.dto.resp.ReindexJobResponse;
import com.example.catalogservice.elasticsearch.service.CatalogReindexService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/catalog/admin/reindex")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMINISTRATOR')")
public class ReindexAdminController {

    private final CatalogReindexService catalogReindexService;

    @PostMapping
    public ResponseEntity<ApiResponse<ReindexJobResponse>> createReindexJob(Authentication authentication) {
        String actorId = authentication != null ? authentication.getName() : "system";
        ReindexJobResponse response = catalogReindexService.createReindexJob(actorId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success("Reindex job initiated successfully", response));
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<ApiResponse<ReindexJobResponse>> getJobStatus(@PathVariable UUID jobId) {
        ReindexJobResponse response = catalogReindexService.getJobStatus(jobId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
