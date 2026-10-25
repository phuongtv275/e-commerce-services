package com.example.inventoryservice.service;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.exception.ConflictException;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.ecommerce.common.exception.ValidationException;
import com.example.inventoryservice.entity.MovementType;
import com.example.inventoryservice.entity.Stock;
import com.example.inventoryservice.entity.StockMovement;
import com.example.inventoryservice.entity.Warehouse;
import com.example.inventoryservice.entity.WarehouseStatus;
import com.example.inventoryservice.repository.StockMovementRepository;
import com.example.inventoryservice.repository.StockRepository;
import com.example.inventoryservice.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockServiceImpl implements StockService {

    private final StockRepository stockRepository;
    private final StockMovementRepository stockMovementRepository;
    private final WarehouseRepository warehouseRepository;
    private final InventoryOutboxService outboxService;

    @Override
    @Transactional(readOnly = true)
    public StockBalance get(UUID skuId, UUID warehouseId) {
        if (skuId == null) {
            throw new ValidationException("skuId is required");
        }
        Warehouse warehouse = resolveWarehouse(warehouseId);
        Stock stock = stockRepository.findBySkuIdAndWarehouseId(skuId, warehouse.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Stock not found for SKU: " + skuId));

        return new StockBalance(
                stock.getSkuId(),
                stock.getWarehouse().getId(),
                stock.getOnHand(),
                stock.getReserved(),
                stock.getAvailableQuantity(),
                stock.getVersion(),
                stock.getUpdatedAt()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StockMovementView> movements(UUID skuId, UUID warehouseId, Pageable pageable) {
        if (skuId == null) {
            throw new ValidationException("skuId is required");
        }
        Warehouse warehouse = resolveWarehouse(warehouseId);
        return stockMovementRepository.findBySkuIdAndWarehouseId(skuId, warehouse.getId(), pageable)
                .map(m -> new StockMovementView(
                        m.getId(),
                        m.getSkuId(),
                        m.getWarehouse().getId(),
                        m.getMovementType().name(),
                        m.getQuantityDelta(),
                        m.getReferenceType(),
                        m.getReferenceId(),
                        m.getBeforeOnHand(),
                        m.getAfterOnHand(),
                        m.getBeforeReserved(),
                        m.getAfterReserved(),
                        m.getActor(),
                        m.getReason(),
                        m.getCorrelationId(),
                        m.getOccurredAt()
                ));
    }

    @Override
    @Transactional
    public StockBalance adjust(StockAdjustmentCommand command, String actor, String correlationId) {
        if (command == null) {
            throw new ValidationException("command is required");
        }
        if (command.skuId() == null) {
            throw new ValidationException("skuId is required");
        }
        if (command.quantityDelta() == 0) {
            throw new ValidationException("quantityDelta must not be zero");
        }
        if (command.referenceId() == null || command.referenceId().isBlank()) {
            throw new ValidationException("referenceId is required");
        }
        if (command.reason() == null || command.reason().isBlank()) {
            throw new ValidationException("reason is required");
        }
        if (command.reason().length() > 500) {
            throw new ValidationException("reason must not exceed 500 characters");
        }

        Warehouse warehouse = resolveWarehouse(command.warehouseId());

        // ReferenceId idempotency check
        Optional<StockMovement> existingMovement = stockMovementRepository
                .findByReferenceTypeAndReferenceIdAndSkuId("ADJUSTMENT", command.referenceId(), command.skuId());
        if (existingMovement.isPresent()) {
            StockMovement prev = existingMovement.get();
            if (prev.getQuantityDelta() == command.quantityDelta()) {
                return get(command.skuId(), warehouse.getId());
            } else {
                throw new ConflictException(ErrorCode.STATE_CONFLICT,
                        "Reference ID already used with different payload for this SKU");
            }
        }

        Instant now = Instant.now();
        Optional<Stock> existingStockOpt = stockRepository.findBySkuIdAndWarehouseId(command.skuId(), warehouse.getId());
        Stock stock;

        if (existingStockOpt.isPresent()) {
            Stock existing = existingStockOpt.get();
            int beforeOnHand = existing.getOnHand();
            int beforeReserved = existing.getReserved();

            int updated = stockRepository.adjustStock(command.skuId(), warehouse.getId(), command.quantityDelta(), now);
            if (updated == 0) {
                throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION,
                        "Stock adjustment would result in invalid on_hand (" + (beforeOnHand + command.quantityDelta())
                                + ") less than reserved (" + beforeReserved + ")");
            }

            stock = stockRepository.findBySkuIdAndWarehouseId(command.skuId(), warehouse.getId()).orElseThrow();

            MovementType movementType = command.quantityDelta() > 0 ? MovementType.INBOUND : MovementType.ADJUSTMENT;
            StockMovement movement = StockMovement.builder()
                    .skuId(command.skuId())
                    .warehouse(warehouse)
                    .movementType(movementType)
                    .quantityDelta(command.quantityDelta())
                    .referenceType("ADJUSTMENT")
                    .referenceId(command.referenceId())
                    .beforeOnHand(beforeOnHand)
                    .afterOnHand(stock.getOnHand())
                    .beforeReserved(beforeReserved)
                    .afterReserved(stock.getReserved())
                    .actor(actor)
                    .reason(command.reason())
                    .correlationId(correlationId)
                    .occurredAt(now)
                    .build();
            stockMovementRepository.save(movement);
        } else {
            if (command.quantityDelta() <= 0) {
                throw new BusinessException(ErrorCode.INVENTORY_INSUFFICIENT,
                        "Initial stock adjustment must be positive");
            }
            stock = Stock.builder()
                    .skuId(command.skuId())
                    .warehouse(warehouse)
                    .onHand(command.quantityDelta())
                    .reserved(0)
                    .version(1L)
                    .updatedAt(now)
                    .build();
            stock = stockRepository.save(stock);

            StockMovement movement = StockMovement.builder()
                    .skuId(command.skuId())
                    .warehouse(warehouse)
                    .movementType(MovementType.INBOUND)
                    .quantityDelta(command.quantityDelta())
                    .referenceType("ADJUSTMENT")
                    .referenceId(command.referenceId())
                    .beforeOnHand(0)
                    .afterOnHand(command.quantityDelta())
                    .beforeReserved(0)
                    .afterReserved(0)
                    .actor(actor)
                    .reason(command.reason())
                    .correlationId(correlationId)
                    .occurredAt(now)
                    .build();
            stockMovementRepository.save(movement);
        }

        outboxService.recordStockChangedEvent(
                stock.getSkuId(),
                stock.getAvailableQuantity(),
                stock.getVersion(),
                stock.getAvailableQuantity() > 0,
                correlationId
        );

        return new StockBalance(
                stock.getSkuId(),
                stock.getWarehouse().getId(),
                stock.getOnHand(),
                stock.getReserved(),
                stock.getAvailableQuantity(),
                stock.getVersion(),
                stock.getUpdatedAt()
        );
    }

    private Warehouse resolveWarehouse(UUID warehouseId) {
        if (warehouseId != null) {
            return warehouseRepository.findById(warehouseId)
                    .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found: " + warehouseId));
        }
        return warehouseRepository.findFirstByIsDefaultTrueAndStatus(WarehouseStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("No default active warehouse configured"));
    }
}
