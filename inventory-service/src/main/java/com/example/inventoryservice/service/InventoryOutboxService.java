package com.example.inventoryservice.service;

import com.ecommerce.common.event.*;
import com.ecommerce.common.outbox.OutboxEvent;
import com.ecommerce.common.outbox.OutboxEventRepository;
import com.ecommerce.common.outbox.OutboxStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryOutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordStockChangedEvent(UUID skuId, int availableQuantity, long version, boolean inStock, String correlationId) {
        InventoryStockChangedEvent event = InventoryStockChangedEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType("inventory.stock.changed")
                .eventVersion(1)
                .occurredAt(Instant.now())
                .correlationId(correlationId)
                .producer("inventory-service")
                .skuId(skuId)
                .availableQuantity(availableQuantity)
                .version(version)
                .inStock(inStock)
                .build();

        saveOutbox("STOCK", skuId.toString(), "inventory.stock.changed", event, correlationId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordReservedEvent(UUID orderId, UUID reservationId, List<ReservationItemCommand> items, Instant expiresAt, String correlationId) {
        List<ReservationItemPayload> payloadItems = items.stream()
                .map(i -> new ReservationItemPayload(i.skuId(), i.quantity()))
                .toList();

        InventoryReservedEvent event = InventoryReservedEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType("inventory.reserved")
                .eventVersion(1)
                .occurredAt(Instant.now())
                .correlationId(correlationId)
                .producer("inventory-service")
                .orderId(orderId)
                .reservationId(reservationId)
                .items(payloadItems)
                .expiresAt(expiresAt)
                .build();

        saveOutbox("RESERVATION", reservationId.toString(), "inventory.reserved", event, correlationId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordReleasedEvent(UUID orderId, UUID reservationId, String reason, String correlationId) {
        InventoryReleasedEvent event = InventoryReleasedEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType("inventory.released")
                .eventVersion(1)
                .occurredAt(Instant.now())
                .correlationId(correlationId)
                .producer("inventory-service")
                .orderId(orderId)
                .reservationId(reservationId)
                .reason(reason)
                .build();

        saveOutbox("RESERVATION", reservationId.toString(), "inventory.released", event, correlationId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordReservationExpiredEvent(UUID reservationId, UUID orderId, String correlationId) {
        ReservationExpiredEvent event = ReservationExpiredEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType("inventory.reservation-expired")
                .eventVersion(1)
                .occurredAt(Instant.now())
                .correlationId(correlationId)
                .producer("inventory-service")
                .reservationId(reservationId)
                .orderId(orderId)
                .build();

        saveOutbox("RESERVATION", reservationId.toString(), "inventory.reservation-expired", event, correlationId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordReservationCommitFailedEvent(UUID reservationId, UUID orderId, String reason, String correlationId) {
        ReservationCommitFailedEvent event = ReservationCommitFailedEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType("inventory.reservation-commit-failed")
                .eventVersion(1)
                .occurredAt(Instant.now())
                .correlationId(correlationId)
                .producer("inventory-service")
                .reservationId(reservationId)
                .orderId(orderId)
                .reason(reason)
                .build();

        String aggregateId = reservationId != null ? reservationId.toString() : orderId.toString();
        saveOutbox("RESERVATION", aggregateId, "inventory.reservation-commit-failed", event, correlationId);
    }

    private void saveOutbox(String aggregateType, String aggregateId, String eventType, Object payloadObj, String correlationId) {
        try {
            String payloadStr = objectMapper.writeValueAsString(payloadObj);
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .id(UUID.randomUUID())
                    .aggregateType(aggregateType)
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .eventVersion(1)
                    .payload(payloadStr)
                    .status(OutboxStatus.PENDING)
                    .retryCount(0)
                    .createdAt(Instant.now())
                    .correlationId(correlationId)
                    .build();

            outboxEventRepository.save(outboxEvent);
            log.info("Recorded Outbox event type={}, aggregateId={}", eventType, aggregateId);
        } catch (Exception e) {
            log.error("Failed to serialize outbox event {}: {}", eventType, e.getMessage(), e);
            throw new RuntimeException("Could not serialize outbox event " + eventType, e);
        }
    }
}
