package com.example.inventoryservice.consumer;

import com.ecommerce.common.event.OrderCancelledEvent;
import com.ecommerce.common.idempotency.IdempotentConsumerHelper;
import com.example.inventoryservice.entity.InventoryQuarantineLog;
import com.example.inventoryservice.repository.InventoryQuarantineLogRepository;
import com.example.inventoryservice.service.ReservationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    private final ReservationService reservationService;
    private final IdempotentConsumerHelper idempotentConsumerHelper;
    private final InventoryQuarantineLogRepository quarantineLogRepository;
    private final ObjectMapper objectMapper;

    @Value("${inventory.kafka.consumer-group:inventory-reservation-group}")
    private String consumerGroup;

    @KafkaListener(
            topics = "${inventory.kafka.topic.order-cancelled:order.cancelled}",
            groupId = "${inventory.kafka.consumer-group:inventory-reservation-group}"
    )
    public void consumeOrderCancelled(ConsumerRecord<String, String> record, Acknowledgment ack) {
        OrderCancelledEvent event;
        try {
            event = objectMapper.readValue(record.value(), OrderCancelledEvent.class);
            validateOrderCancelledEvent(event);
        } catch (Exception e) {
            log.error("Invalid or malformed order.cancelled message: {}", e.getMessage());
            quarantineMalformed(record.value(), "MALFORMED_PAYLOAD_ORDER_CANCELLED", record.key());
            if (ack != null) ack.acknowledge();
            return;
        }

        String eventId = event.getEventId().toString();
        String correlationId = event.getCorrelationId();

        idempotentConsumerHelper.processIdempotent(eventId, consumerGroup, event, evt -> {
            reservationService.restockForEvent(evt.getOrderId(), evt.getReason(), correlationId);
        });

        if (ack != null) {
            ack.acknowledge();
        }
    }

    private void validateOrderCancelledEvent(OrderCancelledEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("Payload cannot be null");
        }
        if (event.getEventType() != null && !"order.cancelled".equals(event.getEventType())) {
            throw new IllegalArgumentException("Invalid eventType: " + event.getEventType());
        }
        if (event.getOrderId() == null) {
            throw new IllegalArgumentException("orderId is required");
        }
        if (event.getEventId() == null) {
            throw new IllegalArgumentException("eventId is required");
        }
    }

    private void quarantineMalformed(String payload, String reason, String key) {
        UUID orderId;
        try {
            orderId = (key != null) ? UUID.fromString(key) : UUID.fromString("00000000-0000-0000-0000-000000000000");
        } catch (Exception e) {
            orderId = UUID.fromString("00000000-0000-0000-0000-000000000000");
        }

        // Do not swallow repository exceptions or acknowledge; must retry if saving to quarantine fails
        quarantineLogRepository.save(InventoryQuarantineLog.builder()
                .orderId(orderId)
                .payload(payload != null ? payload : "{}")
                .errorReason(reason)
                .status("MANUAL_RECONCILIATION_REQUIRED")
                .occurredAt(Instant.now())
                .build());
        log.warn("Quarantined malformed order.cancelled message: reason={}", reason);
    }
}
