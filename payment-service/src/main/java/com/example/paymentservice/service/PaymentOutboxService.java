package com.example.paymentservice.service;

import com.ecommerce.common.event.PaymentFailedEvent;
import com.ecommerce.common.event.PaymentLateSuccessEvent;
import com.ecommerce.common.event.PaymentSucceededEvent;
import com.ecommerce.common.outbox.OutboxEvent;
import com.ecommerce.common.outbox.OutboxEventRepository;
import com.ecommerce.common.outbox.OutboxStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentOutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordPaymentSucceeded(UUID paymentId, UUID orderId, BigDecimal amount, String currency, String providerPaymentId, Instant paidAt, String correlationId) {
        PaymentSucceededEvent event = PaymentSucceededEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType("payment.succeeded")
                .eventVersion(1)
                .occurredAt(Instant.now())
                .correlationId(correlationId)
                .producer("payment-service")
                .paymentId(paymentId)
                .orderId(orderId)
                .amount(amount)
                .currency(currency)
                .providerPaymentId(providerPaymentId)
                .paidAt(paidAt)
                .build();

        saveOutbox("PAYMENT", orderId.toString(), "payment.succeeded", event, correlationId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordPaymentFailed(UUID paymentId, UUID orderId, BigDecimal amount, String failureReason, String correlationId) {
        PaymentFailedEvent event = PaymentFailedEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType("payment.failed")
                .eventVersion(1)
                .occurredAt(Instant.now())
                .correlationId(correlationId)
                .producer("payment-service")
                .paymentId(paymentId)
                .orderId(orderId)
                .amount(amount)
                .failureReason(failureReason)
                .build();

        saveOutbox("PAYMENT", orderId.toString(), "payment.failed", event, correlationId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordPaymentLateSuccess(UUID paymentId, UUID orderId, BigDecimal amount, String currency, String providerPaymentId, String lateReason, String correlationId) {
        PaymentLateSuccessEvent event = PaymentLateSuccessEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType("payment.late-success")
                .eventVersion(1)
                .occurredAt(Instant.now())
                .correlationId(correlationId)
                .producer("payment-service")
                .paymentId(paymentId)
                .orderId(orderId)
                .amount(amount)
                .currency(currency)
                .providerPaymentId(providerPaymentId)
                .lateSuccessReason(lateReason)
                .build();

        saveOutbox("PAYMENT", orderId.toString(), "payment.late-success", event, correlationId);
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
