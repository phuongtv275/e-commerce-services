package com.example.catalogservice.controller;

import com.ecommerce.common.response.ApiResponse;
import com.example.catalogservice.dto.req.CreateSkuRequest;
import com.example.catalogservice.dto.req.UpdateSkuRequest;
import com.example.catalogservice.dto.req.UpdateStatusRequest;
import com.example.catalogservice.dto.resp.SkuResponse;
import com.example.catalogservice.entity.SkuStatus;
import com.example.catalogservice.service.SkuService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/catalog/admin/products/{productId}/skus")
@RequiredArgsConstructor
public class SkuController {

    private final SkuService skuService;

    @PostMapping
    @PreAuthorize("hasAnyRole('CATALOG_STAFF', 'ADMINISTRATOR')")
    public ResponseEntity<ApiResponse<SkuResponse>> createSku(
            @PathVariable UUID productId,
            @Valid @RequestBody CreateSkuRequest request,
            Authentication authentication) {
        String actorId = getActorId(authentication);
        SkuResponse created = skuService.createSku(productId, request, actorId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("SKU created successfully", created));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('CATALOG_STAFF', 'ADMINISTRATOR')")
    public ResponseEntity<ApiResponse<List<SkuResponse>>> getSkusByProductId(@PathVariable UUID productId) {
        List<SkuResponse> skus = skuService.getSkusByProductId(productId);
        return ResponseEntity.ok(ApiResponse.success(skus));
    }

    @PutMapping("/{skuId}")
    @PreAuthorize("hasAnyRole('CATALOG_STAFF', 'ADMINISTRATOR')")
    public ResponseEntity<ApiResponse<SkuResponse>> updateSku(
            @PathVariable UUID productId,
            @PathVariable UUID skuId,
            @Valid @RequestBody UpdateSkuRequest request,
            Authentication authentication) {
        String actorId = getActorId(authentication);
        SkuResponse updated = skuService.updateSku(productId, skuId, request, actorId);
        return ResponseEntity.ok(ApiResponse.success("SKU updated successfully", updated));
    }

    @PatchMapping("/{skuId}/status")
    @PreAuthorize("hasAnyRole('CATALOG_STAFF', 'ADMINISTRATOR')")
    public ResponseEntity<ApiResponse<SkuResponse>> updateSkuStatus(
            @PathVariable UUID productId,
            @PathVariable UUID skuId,
            @Valid @RequestBody UpdateStatusRequest<SkuStatus> request,
            Authentication authentication) {
        String actorId = getActorId(authentication);
        SkuResponse updated = skuService.updateSkuStatus(productId, skuId, request.getStatus(), actorId);
        return ResponseEntity.ok(ApiResponse.success("SKU status updated successfully", updated));
    }

    @DeleteMapping("/{skuId}")
    @PreAuthorize("hasAnyRole('CATALOG_STAFF', 'ADMINISTRATOR')")
    public ResponseEntity<Void> deleteSku(
            @PathVariable UUID productId,
            @PathVariable UUID skuId,
            Authentication authentication) {
        String actorId = getActorId(authentication);
        skuService.deleteSku(productId, skuId, actorId);
        return ResponseEntity.noContent().build();
    }

    private String getActorId(Authentication authentication) {
        return (authentication != null && authentication.getName() != null)
                ? authentication.getName()
                : "anonymous";
    }
}
