package com.example.inventoryservice.controller;

import com.ecommerce.common.response.ApiResponse;
import com.ecommerce.common.response.PagedResponse;
import com.example.inventoryservice.dto.StockAdjustmentRequest;
import com.example.inventoryservice.dto.StockBalanceResponse;
import com.example.inventoryservice.dto.StockMovementResponse;
import com.example.inventoryservice.service.StockAdjustmentCommand;
import com.example.inventoryservice.service.StockBalance;
import com.example.inventoryservice.service.StockMovementView;
import com.example.inventoryservice.service.StockService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inventory/stocks")
@RequiredArgsConstructor
public class StockController {

    private final StockService stockService;

    @PostMapping("/{skuId}/adjust")
    @PreAuthorize("hasAnyRole('INVENTORY_STAFF', 'ADMINISTRATOR')")
    public ResponseEntity<ApiResponse<StockBalanceResponse>> adjustStock(
            @PathVariable UUID skuId,
            @Valid @RequestBody StockAdjustmentRequest request,
            Authentication authentication) {

        String actor = (authentication != null && authentication.getName() != null)
                ? authentication.getName() : "system";
        String correlationId = MDC.get("correlationId");

        StockAdjustmentCommand cmd = new StockAdjustmentCommand(
                skuId,
                request.warehouseId(),
                request.quantityDelta(),
                request.referenceId(),
                request.reason()
        );

        StockBalance balance = stockService.adjust(cmd, actor, correlationId);
        return ResponseEntity.ok(ApiResponse.success(StockBalanceResponse.from(balance)));
    }

    @GetMapping("/{skuId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<StockBalanceResponse>> getStock(
            @PathVariable UUID skuId,
            @RequestParam(required = false) UUID warehouseId) {

        StockBalance balance = stockService.get(skuId, warehouseId);
        return ResponseEntity.ok(ApiResponse.success(StockBalanceResponse.from(balance)));
    }

    @GetMapping("/{skuId}/movements")
    @PreAuthorize("hasAnyRole('INVENTORY_STAFF', 'ADMINISTRATOR')")
    public ResponseEntity<ApiResponse<PagedResponse<StockMovementResponse>>> getMovements(
            @PathVariable UUID skuId,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        int validPage = Math.max(page, 0);
        int validSize = Math.min(Math.max(size, 1), 100);

        Page<StockMovementView> pageResult = stockService.movements(
                skuId,
                warehouseId,
                PageRequest.of(validPage, validSize, Sort.by(Sort.Direction.DESC, "occurredAt", "id"))
        );

        PagedResponse<StockMovementResponse> pagedResponse = PagedResponse.<StockMovementResponse>builder()
                .content(pageResult.getContent().stream().map(StockMovementResponse::from).toList())
                .pageNumber(pageResult.getNumber())
                .pageSize(pageResult.getSize())
                .totalElements(pageResult.getTotalElements())
                .totalPages(pageResult.getTotalPages())
                .isLast(pageResult.isLast())
                .isFirst(pageResult.isFirst())
                .build();

        return ResponseEntity.ok(ApiResponse.success(pagedResponse));
    }
}
