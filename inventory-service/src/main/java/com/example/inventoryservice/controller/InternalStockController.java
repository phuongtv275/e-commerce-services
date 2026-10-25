package com.example.inventoryservice.controller;

import com.ecommerce.common.response.ApiResponse;
import com.example.inventoryservice.dto.BatchStockRequest;
import com.example.inventoryservice.dto.BatchStockResponse;
import com.example.inventoryservice.service.StockService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/internal/inventory/stocks")
@RequiredArgsConstructor
public class InternalStockController {

    private final StockService stockService;

    @PostMapping("/batch")
    @PreAuthorize("hasRole('SERVICE_ORDER')")
    public ResponseEntity<ApiResponse<BatchStockResponse>> getBatchStockAvailability(
            @Valid @RequestBody BatchStockRequest request) {
        log.info("Received internal batch stock availability request for {} SKU IDs",
                request.skuIds() != null ? request.skuIds().size() : 0);
        BatchStockResponse response = stockService.getBatchStockAvailability(request.skuIds());
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
