package com.example.inventoryservice.consumer;

import com.ecommerce.common.event.PaymentFailedEvent;
import com.ecommerce.common.event.PaymentSucceededEvent;
import com.ecommerce.common.exception.ConflictException;
import com.ecommerce.common.idempotency.IdempotentConsumerHelper;
import com.example.inventoryservice.entity.InventoryQuarantineLog;
import com.example.inventoryservice.entity.Reservation;
import com.example.inventoryservice.entity.ReservationStatus;
import com.example.inventoryservice.repository.InventoryQuarantineLogRepository;
import com.example.inventoryservice.repository.ReservationRepository;
import com.example.inventoryservice.service.ReservationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {

    private final ReservationService reservationService;
    private final ReservationRepository reservationRepository;
    private final IdempotentConsumerHelper idempotentConsumerHelper;
    private final InventoryQuarantineLogRepository quarantineLogRepository;
    private final ObjectMapper objectMapper;

    @Value("${inventory.kafka.consumer-group:inventory-reservation-group}")
    private String consumerGroup;

    @KafkaListener(
            topics = "${inventory.kafka.topic.payment-succeeded:payment.succeeded}",
            groupId = "${inventory.kafka.consumer-group:inventory-reservation-group}"
    )
    public void consumePaymentSucceeded(
            ConsumerRecord<String, String> record,
            Acknowledgment ack,
            @Header(name = KafkaHeaders.DELIVERY_ATTEMPT, required = false) Integer deliveryAttempt) {

        PaymentSucceededEvent event;
        try {
            event = objectMapper.readValue(record.value(), PaymentSucceededEvent.class);
            validatePaymentSucceededEvent(event);
        } catch (Exception e) {
            log.error("Invalid or malformed payment.succeeded message: {}", e.getMessage());
            quarantineMalformed(record.value(), "MALFORMED_PAYLOAD_PAYMENT_SUCCEEDED", record.key());
            if (ack != null) ack.acknowledge();
            return;
        }

        String eventId = event.getEventId() != null ? event.getEventId().toString() : record.key();
        String correlationId = event.getCorrelationId();

        idempotentConsumerHelper.processIdempotent(eventId, consumerGroup, event, evt -> {
            UUID orderId = evt.getOrderId();
            Optional<Reservation> resOpt = reservationRepository.findByOrderId(orderId);

            if (resOpt.isEmpty()) {
                int attempt = deliveryAttempt != null ? deliveryAttempt : 1;
                if (attempt < 3) {
                    throw new RuntimeException("Reservation not found for orderId " + orderId + ", attempt " + attempt + ", retrying");
                } else {
                    reservationService.quarantineCommitFailure(
                            orderId, "RESERVATION_NOT_FOUND_AFTER_PAYMENT", record.value(), correlationId);
                    return;
                }
            }

            Reservation reservation = resOpt.get();
            if (reservation.getStatus() == ReservationStatus.COMMITTED) {
                log.info("Reservation for orderId {} is already COMMITTED, duplicate no-op", orderId);
                return;
            }

            try {
                reservationService.commit(orderId, correlationId);
            } catch (ConflictException ex) {
                log.warn("Commit conflict on orderId {}: {}", orderId, ex.getMessage());
            }
        });

        if (ack != null) {
            ack.acknowledge();
        }
    }

    @KafkaListener(
            topics = "${inventory.kafka.topic.payment-failed:payment.failed}",
            groupId = "${inventory.kafka.consumer-group:inventory-reservation-group}"
    )
    public void consumePaymentFailed(ConsumerRecord<String, String> record, Acknowledgment ack) {
        PaymentFailedEvent event;
        try {
            event = objectMapper.readValue(record.value(), PaymentFailedEvent.class);
            validatePaymentFailedEvent(event);
        } catch (Exception e) {
            log.error("Invalid or malformed payment.failed message: {}", e.getMessage());
            quarantineMalformed(record.value(), "MALFORMED_PAYLOAD_PAYMENT_FAILED", record.key());
            if (ack != null) ack.acknowledge();
            return;
        }

        String eventId = event.getEventId().toString();
        String correlationId = event.getCorrelationId();

        idempotentConsumerHelper.processIdempotent(eventId, consumerGroup, event, evt -> {
            reservationService.releaseForEvent(evt.getOrderId(), evt.getFailureReason(), correlationId);
        });

        if (ack != null) {
            ack.acknowledge();
        }
    }

    private void validatePaymentSucceededEvent(PaymentSucceededEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("Payload cannot be null");
        }
        if (event.getEventType() != null && !"payment.succeeded".equals(event.getEventType())) {
            throw new IllegalArgumentException("Invalid eventType: " + event.getEventType());
        }
        if (event.getOrderId() == null) {
            throw new IllegalArgumentException("orderId is required");
        }
        if (event.getPaymentId() == null) {
            throw new IllegalArgumentException("paymentId is required");
        }
        if (event.getEventId() == null) {
            throw new IllegalArgumentException("eventId is required");
        }
    }

    private void validatePaymentFailedEvent(PaymentFailedEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("Payload cannot be null");
        }
        if (event.getEventType() != null && !"payment.failed".equals(event.getEventType())) {
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
        log.warn("Quarantined malformed message: reason={}", reason);
    }
}
