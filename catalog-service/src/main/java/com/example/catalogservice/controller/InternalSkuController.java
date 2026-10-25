package com.example.catalogservice.controller;

import com.ecommerce.common.response.ApiResponse;
import com.example.catalogservice.dto.req.InternalBatchSkuRequest;
import com.example.catalogservice.dto.resp.InternalBatchSkuResponse;
import com.example.catalogservice.dto.resp.InternalSkuResponse;
import com.example.catalogservice.service.SkuService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/internal/catalog/skus")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SERVICE_ORDER')")
public class InternalSkuController {

    private final SkuService skuService;

    @GetMapping("/{skuId}")
    public ResponseEntity<ApiResponse<InternalSkuResponse>> getInternalSku(@PathVariable UUID skuId) {
        InternalSkuResponse response = skuService.getInternalSku(skuId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/batch")
    public ResponseEntity<ApiResponse<InternalBatchSkuResponse>> getInternalBatchSkus(
            @Valid @RequestBody InternalBatchSkuRequest request) {
        InternalBatchSkuResponse response = skuService.getInternalBatchSkus(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
