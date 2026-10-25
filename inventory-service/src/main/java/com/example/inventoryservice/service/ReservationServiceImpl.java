package com.example.inventoryservice.service;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.exception.ConflictException;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.ecommerce.common.exception.ValidationException;
import com.example.inventoryservice.entity.*;
import com.example.inventoryservice.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationServiceImpl implements ReservationService {

    private final ReservationRepository reservationRepository;
    private final ReservationItemRepository reservationItemRepository;
    private final StockRepository stockRepository;
    private final StockMovementRepository stockMovementRepository;
    private final WarehouseRepository warehouseRepository;
    private final InventoryQuarantineLogRepository quarantineLogRepository;
    private final InventoryOutboxService outboxService;

    @org.springframework.beans.factory.annotation.Value("${inventory.reservation.ttl:15m}")
    private Duration reservationTtl = Duration.ofMinutes(15);

    @Override
    @Transactional
    public ReservationResult reserve(UUID orderId, List<ReservationItemCommand> items, String correlationId) {
        if (orderId == null) {
            throw new ValidationException("orderId is required");
        }
        if (items == null || items.isEmpty()) {
            throw new ValidationException("items list cannot be empty");
        }
        if (items.size() > 100) {
            throw new ValidationException("items list cannot exceed 100 items");
        }

        // Consolidate duplicate SKUs and check overflow
        Map<UUID, Integer> consolidated = new LinkedHashMap<>();
        for (ReservationItemCommand item : items) {
            if (item.skuId() == null) {
                throw new ValidationException("skuId is required for each item");
            }
            if (item.quantity() <= 0) {
                throw new ValidationException("quantity must be greater than zero for sku: " + item.skuId());
            }
            try {
                consolidated.merge(item.skuId(), item.quantity(), Math::addExact);
            } catch (ArithmeticException e) {
                throw new ValidationException("Quantity sum overflow for SKU: " + item.skuId());
            }
        }

        List<ReservationItemCommand> consolidatedList = consolidated.entrySet().stream()
                .map(e -> new ReservationItemCommand(e.getKey(), e.getValue()))
                .toList();

        // Check if reservation exists for this orderId
        Optional<Reservation> existingOpt = reservationRepository.findByOrderId(orderId);
        if (existingOpt.isPresent()) {
            return replayOrConflict(existingOpt.get(), consolidated, orderId);
        }

        Warehouse warehouse = warehouseRepository.findFirstByIsDefaultTrueAndStatus(WarehouseStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("No default active warehouse configured"));

        // Sort items by SKU UUID ascending to avoid deadlocks
        List<ReservationItemCommand> sortedItems = consolidatedList.stream()
                .sorted(Comparator.comparing(ReservationItemCommand::skuId))
                .toList();

        Instant now = Instant.now();
        Instant expiresAt = now.plus(reservationTtl != null ? reservationTtl : Duration.ofMinutes(15));

        UUID reservationId = UUID.randomUUID();
        int inserted = reservationRepository.insertIfNotExists(
                reservationId, orderId, warehouse.getId(), ReservationStatus.PENDING.name(), expiresAt, correlationId, now
        );
        if (inserted == 0) {
            Reservation existing = reservationRepository.findByOrderId(orderId)
                    .orElseThrow(() -> new ConflictException(ErrorCode.STATE_CONFLICT,
                            "Concurrent reservation in progress for orderId " + orderId));
            return replayOrConflict(existing, consolidated, orderId);
        }

        Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();
        if (reservation.getItems() == null) {
            reservation.setItems(new ArrayList<>());
        }

        for (ReservationItemCommand item : sortedItems) {
            int rows = stockRepository.reserveStock(item.skuId(), warehouse.getId(), item.quantity(), now);
            if (rows == 0) {
                throw new BusinessException(ErrorCode.INVENTORY_INSUFFICIENT,
                        "Insufficient stock or SKU not found for skuId: " + item.skuId());
            }

            Stock stock = stockRepository.findBySkuIdAndWarehouseId(item.skuId(), warehouse.getId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVENTORY_INSUFFICIENT,
                            "Stock row not found for skuId: " + item.skuId()));

            ReservationItem resItem = ReservationItem.builder()
                    .reservation(reservation)
                    .skuId(item.skuId())
                    .quantity(item.quantity())
                    .build();
            reservationItemRepository.save(resItem);
            reservation.getItems().add(resItem);

            StockMovement movement = StockMovement.builder()
                    .skuId(item.skuId())
                    .warehouse(warehouse)
                    .movementType(MovementType.RESERVE)
                    .quantityDelta(0)
                    .referenceType("RESERVATION")
                    .referenceId(reservation.getId().toString())
                    .beforeOnHand(stock.getOnHand())
                    .afterOnHand(stock.getOnHand())
                    .beforeReserved(stock.getReserved() - item.quantity())
                    .afterReserved(stock.getReserved())
                    .actor("system")
                    .reason("Order reservation")
                    .correlationId(correlationId)
                    .occurredAt(now)
                    .build();
            stockMovementRepository.save(movement);

            outboxService.recordStockChangedEvent(
                    stock.getSkuId(),
                    stock.getAvailableQuantity(),
                    stock.getVersion(),
                    stock.getAvailableQuantity() > 0,
                    correlationId
            );
        }

        outboxService.recordReservedEvent(orderId, reservation.getId(), sortedItems, expiresAt, correlationId);

        return new ReservationResult(
                reservation.getId(),
                reservation.getOrderId(),
                reservation.getStatus().name(),
                reservation.getExpiresAt(),
                sortedItems
        );
    }

    @Override
    @Transactional
    public ReservationResult release(UUID orderId, String reason, String correlationId) {
        if (orderId == null) {
            throw new ValidationException("orderId is required");
        }

        Reservation reservation = reservationRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found for orderId: " + orderId));

        if (reservation.getStatus() == ReservationStatus.RELEASED) {
            return toResult(reservation);
        }
        if (reservation.getStatus() != ReservationStatus.PENDING) {
            throw new ConflictException(ErrorCode.STATE_CONFLICT,
                    "Cannot release reservation in state: " + reservation.getStatus());
        }

        Instant now = Instant.now();
        int rows = reservationRepository.transitionToReleased(reservation.getId(), now);
        if (rows == 0) {
            Reservation current = reservationRepository.findById(reservation.getId()).orElseThrow();
            if (current.getStatus() == ReservationStatus.RELEASED) {
                return toResult(current);
            }
            throw new ConflictException(ErrorCode.STATE_CONFLICT,
                    "Failed to release reservation, current state: " + current.getStatus());
        }

        for (ReservationItem item : reservation.getItems()) {
            stockRepository.releaseReservedStock(item.getSkuId(), reservation.getWarehouse().getId(), item.getQuantity(), now);
            Stock stock = stockRepository.findBySkuIdAndWarehouseId(item.getSkuId(), reservation.getWarehouse().getId())
                    .orElseThrow();

            StockMovement movement = StockMovement.builder()
                    .skuId(item.getSkuId())
                    .warehouse(reservation.getWarehouse())
                    .movementType(MovementType.RELEASE)
                    .quantityDelta(0)
                    .referenceType("RESERVATION")
                    .referenceId(reservation.getId().toString())
                    .beforeOnHand(stock.getOnHand())
                    .afterOnHand(stock.getOnHand())
                    .beforeReserved(stock.getReserved() + item.getQuantity())
                    .afterReserved(stock.getReserved())
                    .actor("system")
                    .reason(reason != null ? reason : "Order release")
                    .correlationId(correlationId)
                    .occurredAt(now)
                    .build();
            stockMovementRepository.save(movement);

            outboxService.recordStockChangedEvent(
                    stock.getSkuId(),
                    stock.getAvailableQuantity(),
                    stock.getVersion(),
                    stock.getAvailableQuantity() > 0,
                    correlationId
            );
        }

        outboxService.recordReleasedEvent(
                orderId,
                reservation.getId(),
                reason != null ? reason : "Order release",
                correlationId
        );

        reservation.setStatus(ReservationStatus.RELEASED);
        return toResult(reservation);
    }

    @Override
    @Transactional
    public void commit(UUID orderId, String correlationId) {
        if (orderId == null) {
            throw new ValidationException("orderId is required");
        }

        Reservation reservation = reservationRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found for orderId: " + orderId));

        if (reservation.getStatus() == ReservationStatus.COMMITTED) {
            return; // Idempotent no-op
        }

        if (reservation.getStatus() == ReservationStatus.EXPIRED || reservation.getStatus() == ReservationStatus.RELEASED) {
            String failReason = (reservation.getStatus() == ReservationStatus.EXPIRED)
                    ? "RESERVATION_EXPIRED" : "RESERVATION_ALREADY_RELEASED";
            outboxService.recordReservationCommitFailedEvent(reservation.getId(), orderId, failReason, correlationId);
            log.info("Reservation for orderId {} is already {}, recorded failed commit outbox event", orderId, reservation.getStatus());
            return;
        }

        if (reservation.getStatus() != ReservationStatus.PENDING) {
            throw new ConflictException(ErrorCode.STATE_CONFLICT,
                    "Cannot commit reservation in state: " + reservation.getStatus());
        }

        Instant now = Instant.now();
        if (reservation.getExpiresAt() != null && reservation.getExpiresAt().isBefore(now)) {
            boolean expired = expireSingle(reservation.getId(), now);
            if (expired) {
                outboxService.recordReservationCommitFailedEvent(
                        reservation.getId(), orderId, "RESERVATION_EXPIRED", correlationId);
                log.info("Reservation for orderId {} was PENDING but expired at {}, released and recorded commit failed",
                        orderId, reservation.getExpiresAt());
                return;
            }
            Reservation current = reservationRepository.findById(reservation.getId()).orElseThrow();
            if (current.getStatus() == ReservationStatus.COMMITTED) {
                log.info("Reservation for orderId {} committed concurrently during expiry check; no commit-failed event", orderId);
                return;
            }
            if (current.getStatus() == ReservationStatus.EXPIRED || current.getStatus() == ReservationStatus.RELEASED) {
                String failReason = (current.getStatus() == ReservationStatus.EXPIRED)
                        ? "RESERVATION_EXPIRED" : "RESERVATION_ALREADY_RELEASED";
                outboxService.recordReservationCommitFailedEvent(current.getId(), orderId, failReason, correlationId);
                return;
            }
            throw new ConflictException(ErrorCode.STATE_CONFLICT,
                    "Failed to expire or commit reservation, current state: " + current.getStatus());
        }
        int rows = reservationRepository.transitionToCommitted(reservation.getId(), now);
        if (rows == 0) {
            Reservation current = reservationRepository.findById(reservation.getId()).orElseThrow();
            if (current.getStatus() == ReservationStatus.COMMITTED) {
                return;
            }
            if (current.getStatus() == ReservationStatus.EXPIRED || current.getStatus() == ReservationStatus.RELEASED) {
                String failReason = (current.getStatus() == ReservationStatus.EXPIRED)
                        ? "RESERVATION_EXPIRED" : "RESERVATION_ALREADY_RELEASED";
                outboxService.recordReservationCommitFailedEvent(current.getId(), orderId, failReason, correlationId);
                return;
            }
            if (current.getStatus() == ReservationStatus.PENDING && current.getExpiresAt() != null && !current.getExpiresAt().isAfter(now)) {
                boolean expired = expireSingle(current.getId(), now);
                if (expired) {
                    outboxService.recordReservationCommitFailedEvent(current.getId(), orderId, "RESERVATION_EXPIRED", correlationId);
                    log.info("Reservation for orderId {} was PENDING but expired at commit CAS, released stock and recorded commit failed", orderId);
                    return;
                }
                Reservation reloaded = reservationRepository.findById(current.getId()).orElseThrow();
                if (reloaded.getStatus() == ReservationStatus.COMMITTED) {
                    return;
                }
                if (reloaded.getStatus() == ReservationStatus.EXPIRED || reloaded.getStatus() == ReservationStatus.RELEASED) {
                    String failReason = (reloaded.getStatus() == ReservationStatus.EXPIRED)
                            ? "RESERVATION_EXPIRED" : "RESERVATION_ALREADY_RELEASED";
                    outboxService.recordReservationCommitFailedEvent(reloaded.getId(), orderId, failReason, correlationId);
                    return;
                }
            }
            throw new ConflictException(ErrorCode.STATE_CONFLICT,
                    "Failed to commit reservation, current state: " + current.getStatus());
        }

        for (ReservationItem item : reservation.getItems()) {
            stockRepository.commitStock(item.getSkuId(), reservation.getWarehouse().getId(), item.getQuantity(), now);
            Stock stock = stockRepository.findBySkuIdAndWarehouseId(item.getSkuId(), reservation.getWarehouse().getId())
                    .orElseThrow();

            StockMovement movement = StockMovement.builder()
                    .skuId(item.getSkuId())
                    .warehouse(reservation.getWarehouse())
                    .movementType(MovementType.OUTBOUND)
                    .quantityDelta(-item.getQuantity())
                    .referenceType("RESERVATION")
                    .referenceId(reservation.getId().toString())
                    .beforeOnHand(stock.getOnHand() + item.getQuantity())
                    .afterOnHand(stock.getOnHand())
                    .beforeReserved(stock.getReserved() + item.getQuantity())
                    .afterReserved(stock.getReserved())
                    .actor("system")
                    .reason("Payment committed")
                    .correlationId(correlationId)
                    .occurredAt(now)
                    .build();
            stockMovementRepository.save(movement);

            outboxService.recordStockChangedEvent(
                    stock.getSkuId(),
                    stock.getAvailableQuantity(),
                    stock.getVersion(),
                    stock.getAvailableQuantity() > 0,
                    correlationId
            );
        }
    }

    @Override
    @Transactional
    public void releaseForEvent(UUID orderId, String reason, String correlationId) {
        if (orderId == null) {
            return;
        }
        Optional<Reservation> resOpt = reservationRepository.findByOrderId(orderId);
        if (resOpt.isEmpty()) {
            log.info("releaseForEvent: No reservation found for orderId {}, skipping", orderId);
            return;
        }
        Reservation reservation = resOpt.get();
        if (reservation.getStatus() == ReservationStatus.PENDING) {
            release(orderId, reason, correlationId);
        } else {
            log.info("releaseForEvent: Reservation for orderId {} in state {}, skipping", orderId, reservation.getStatus());
        }
    }

    @Override
    @Transactional
    public void restockForEvent(UUID orderId, String reason, String correlationId) {
        if (orderId == null) {
            return;
        }
        Optional<Reservation> resOpt = reservationRepository.findByOrderId(orderId);
        if (resOpt.isEmpty()) {
            log.info("restockForEvent: No reservation found for orderId {}, skipping", orderId);
            return;
        }
        Reservation reservation = resOpt.get();
        if (reservation.getStatus() == ReservationStatus.RESTOCKED) {
            return; // Duplicate RESTOCKED is no-op
        }
        if (reservation.getStatus() == ReservationStatus.PENDING) {
            release(orderId, reason, correlationId);
            return;
        }
        if (reservation.getStatus() != ReservationStatus.COMMITTED) {
            log.info("restockForEvent: Reservation for orderId {} in state {}, cannot restock", orderId, reservation.getStatus());
            return;
        }

        Instant now = Instant.now();
        int rows = reservationRepository.transitionToRestocked(reservation.getId(), now);
        if (rows == 0) {
            return; // Lost CAS race
        }

        for (ReservationItem item : reservation.getItems()) {
            stockRepository.restockStock(item.getSkuId(), reservation.getWarehouse().getId(), item.getQuantity(), now);
            Stock stock = stockRepository.findBySkuIdAndWarehouseId(item.getSkuId(), reservation.getWarehouse().getId())
                    .orElseThrow();

            StockMovement movement = StockMovement.builder()
                    .skuId(item.getSkuId())
                    .warehouse(reservation.getWarehouse())
                    .movementType(MovementType.RESTOCK)
                    .quantityDelta(item.getQuantity())
                    .referenceType("RESERVATION")
                    .referenceId(reservation.getId().toString())
                    .beforeOnHand(stock.getOnHand() - item.getQuantity())
                    .afterOnHand(stock.getOnHand())
                    .beforeReserved(stock.getReserved())
                    .afterReserved(stock.getReserved())
                    .actor("system")
                    .reason(reason != null ? reason : "Order cancelled restock")
                    .correlationId(correlationId)
                    .occurredAt(now)
                    .build();
            stockMovementRepository.save(movement);

            outboxService.recordStockChangedEvent(
                    stock.getSkuId(),
                    stock.getAvailableQuantity(),
                    stock.getVersion(),
                    stock.getAvailableQuantity() > 0,
                    correlationId
            );
        }
    }

    @Override
    @Transactional
    public int expireDue(Instant now, int batchSize) {
        List<UUID> expiredIds = reservationRepository.findExpiredReservationIdsForUpdate(now, batchSize);
        int count = 0;
        for (UUID id : expiredIds) {
            if (expireSingle(id, now)) {
                count++;
            }
        }
        return count;
    }

    @Transactional
    public boolean expireSingle(UUID id, Instant now) {
        int rows = reservationRepository.transitionToExpired(id, now);
        if (rows == 0) {
            return false;
        }
        Reservation reservation = reservationRepository.findById(id).orElseThrow();
        for (ReservationItem item : reservation.getItems()) {
            stockRepository.releaseReservedStock(item.getSkuId(), reservation.getWarehouse().getId(), item.getQuantity(), now);
            Stock stock = stockRepository.findBySkuIdAndWarehouseId(item.getSkuId(), reservation.getWarehouse().getId())
                    .orElseThrow();

            StockMovement movement = StockMovement.builder()
                    .skuId(item.getSkuId())
                    .warehouse(reservation.getWarehouse())
                    .movementType(MovementType.RELEASE)
                    .quantityDelta(0)
                    .referenceType("EXPIRY")
                    .referenceId(reservation.getId().toString())
                    .beforeOnHand(stock.getOnHand())
                    .afterOnHand(stock.getOnHand())
                    .beforeReserved(stock.getReserved() + item.getQuantity())
                    .afterReserved(stock.getReserved())
                    .actor("system")
                    .reason("RESERVATION_EXPIRED")
                    .correlationId(reservation.getCorrelationId())
                    .occurredAt(now)
                    .build();
            stockMovementRepository.save(movement);

            outboxService.recordStockChangedEvent(
                    stock.getSkuId(),
                    stock.getAvailableQuantity(),
                    stock.getVersion(),
                    stock.getAvailableQuantity() > 0,
                    reservation.getCorrelationId()
            );
        }

        outboxService.recordReservationExpiredEvent(
                reservation.getId(),
                reservation.getOrderId(),
                reservation.getCorrelationId()
        );

        return true;
    }

    @Override
    @Transactional
    public void quarantineCommitFailure(UUID orderId, String reason, String payloadJson, String correlationId) {
        InventoryQuarantineLog quarantineLog = InventoryQuarantineLog.builder()
                .orderId(orderId)
                .payload(payloadJson != null ? payloadJson : "{}")
                .errorReason(reason)
                .status("MANUAL_RECONCILIATION_REQUIRED")
                .occurredAt(Instant.now())
                .build();
        quarantineLogRepository.save(quarantineLog);

        outboxService.recordReservationCommitFailedEvent(null, orderId, reason, correlationId);
        log.warn("Quarantined commit failure for orderId {}: {}", orderId, reason);
    }

    private ReservationResult replayOrConflict(Reservation existing, Map<UUID, Integer> consolidated, UUID orderId) {
        if (existing.getStatus() == ReservationStatus.PENDING) {
            Map<UUID, Integer> existingItemsMap = existing.getItems().stream()
                    .collect(Collectors.toMap(ReservationItem::getSkuId, ReservationItem::getQuantity));
            if (existingItemsMap.equals(consolidated)) {
                return toResult(existing);
            } else {
                throw new ConflictException(ErrorCode.STATE_CONFLICT,
                        "Reservation for orderId " + orderId + " already exists with different items");
            }
        } else {
            throw new ConflictException(ErrorCode.STATE_CONFLICT,
                    "Reservation for orderId " + orderId + " is in terminal state: " + existing.getStatus());
        }
    }

    private ReservationResult toResult(Reservation reservation) {
        List<ReservationItemCommand> itemCommands = reservation.getItems().stream()
                .map(i -> new ReservationItemCommand(i.getSkuId(), i.getQuantity()))
                .toList();
        return new ReservationResult(
                reservation.getId(),
                reservation.getOrderId(),
                reservation.getStatus().name(),
                reservation.getExpiresAt(),
                itemCommands
        );
    }
}
