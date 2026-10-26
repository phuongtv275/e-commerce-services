package com.example.paymentservice.consumer;

import com.ecommerce.common.event.OrderCancelledEvent;
import com.ecommerce.common.event.DomainEvent;
import com.ecommerce.common.event.PaymentLateSuccessEvent;
import com.ecommerce.common.event.ReservationCommitFailedEvent;
import com.ecommerce.common.event.ReservationExpiredEvent;
import com.ecommerce.common.constant.SecurityConstants;
import com.ecommerce.common.idempotency.IdempotentConsumerHelper;
import com.example.paymentservice.entity.Payment;
import com.example.paymentservice.entity.PaymentAuditLog;
import com.example.paymentservice.entity.PaymentStatus;
import com.example.paymentservice.refund.service.RefundService;
import com.example.paymentservice.repository.PaymentAuditLogRepository;
import com.example.paymentservice.repository.PaymentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentSagaConsumer {

    private final RefundService refundService;
    private final PaymentRepository paymentRepository;
    private final PaymentAuditLogRepository auditLogRepository;
    private final IdempotentConsumerHelper idempotentConsumerHelper;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    @Value("${payment.kafka.consumer-group:payment-service-group}")
    private String consumerGroup = "payment-service-group";

    @KafkaListener(
            topics = "${payment.kafka.topic.payment-late-success:payment.late-success}",
            groupId = "${payment.kafka.consumer-group:payment-service-group}"
    )
    public void consumePaymentLateSuccess(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.info("Received payment.late-success record key={}", record.key());
        PaymentLateSuccessEvent event = readEvent(record, PaymentLateSuccessEvent.class, "payment.late-success");
        if (event.getOrderId() == null) throw new IllegalArgumentException("payment.late-success is missing orderId");

        withCorrelationId(event.getCorrelationId(), () -> {
            String eventId = event.getEventId().toString();
            idempotentConsumerHelper.processIdempotent(eventId, consumerGroup, event, evt ->
                    refundService.processAutoRefund(evt.getOrderId(), null,
                            "Late payment success: " + evt.getLateSuccessReason(), evt.getCorrelationId()));
            if (ack != null) ack.acknowledge();
        });
    }

    @KafkaListener(
            topics = "${payment.kafka.topic.order-cancelled:order.cancelled}",
            groupId = "${payment.kafka.consumer-group:payment-service-group}"
    )
    public void consumeOrderCancelled(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.info("Received order.cancelled record key={}", record.key());
        OrderCancelledEvent event = readEvent(record, OrderCancelledEvent.class, "order.cancelled");
        if (event.getOrderId() == null) throw new IllegalArgumentException("order.cancelled is missing orderId");

        withCorrelationId(event.getCorrelationId(), () -> {
            if (!event.isRefundRequired()) {
                log.info("order.cancelled for orderId={} does not require refund. No-op.", event.getOrderId());
                if (ack != null) ack.acknowledge();
                return;
            }
            idempotentConsumerHelper.processIdempotent(event.getEventId().toString(), consumerGroup, event, evt -> {
                refundService.processAutoRefund(evt.getOrderId(), null, evt.getReason(), evt.getCorrelationId());
            });
            if (ack != null) ack.acknowledge();
        });
    }

    @KafkaListener(
            topics = "${payment.kafka.topic.reservation-commit-failed:inventory.reservation-commit-failed}",
            groupId = "${payment.kafka.consumer-group:payment-service-group}"
    )
    public void consumeReservationCommitFailed(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.info("Received inventory.reservation-commit-failed record key={}", record.key());
        ReservationCommitFailedEvent event = readEvent(record, ReservationCommitFailedEvent.class,
                "inventory.reservation-commit-failed");
        if (event.getOrderId() == null) throw new IllegalArgumentException("reservation-commit-failed is missing orderId");

        withCorrelationId(event.getCorrelationId(), () -> {
            idempotentConsumerHelper.processIdempotent(event.getEventId().toString(), consumerGroup, event, evt -> {
                refundService.processAutoRefund(evt.getOrderId(), null,
                        "Reservation commit failed: " + evt.getReason(), evt.getCorrelationId());
            });
            if (ack != null) ack.acknowledge();
        });
    }

    @KafkaListener(
            topics = "${payment.kafka.topic.reservation-expired:inventory.reservation-expired}",
            groupId = "${payment.kafka.consumer-group:payment-service-group}"
    )
    public void consumeReservationExpired(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.info("Received inventory.reservation-expired record key={}", record.key());
        ReservationExpiredEvent event = readEvent(record, ReservationExpiredEvent.class,
                "inventory.reservation-expired");
        if (event.getOrderId() == null) throw new IllegalArgumentException("reservation-expired is missing orderId");

        withCorrelationId(event.getCorrelationId(), () -> {
            idempotentConsumerHelper.processIdempotent(event.getEventId().toString(), consumerGroup, event, evt -> {
                TransactionTemplate tt = new TransactionTemplate(transactionManager);
                tt.executeWithoutResult(status -> {
                    Optional<Payment> paymentOpt = paymentRepository.findByOrderIdForUpdate(evt.getOrderId());
                    if (paymentOpt.isPresent()) {
                        Payment payment = paymentOpt.get();
                        if (payment.getStatus() == PaymentStatus.PENDING) {
                            payment.setStatus(PaymentStatus.EXPIRED);
                            paymentRepository.save(payment);

                            auditLogRepository.save(PaymentAuditLog.builder()
                                    .paymentId(payment.getId())
                                    .action("PAYMENT_EXPIRED_BY_RESERVATION")
                                    .oldStatus(PaymentStatus.PENDING.name())
                                    .newStatus(PaymentStatus.EXPIRED.name())
                                    .actor("SYSTEM")
                                    .occurredAt(Instant.now())
                                    .build());
                            log.info("Payment for orderId={} marked EXPIRED due to reservation expiry", evt.getOrderId());
                        }
                    }
                });
            });
            if (ack != null) ack.acknowledge();
        });
    }

    private void withCorrelationId(String correlationId, Runnable action) {
        try {
            if (correlationId != null) MDC.put(SecurityConstants.MDC_CORRELATION_ID, correlationId);
            action.run();
        } finally {
            MDC.remove(SecurityConstants.MDC_CORRELATION_ID);
        }
    }

    private <T extends DomainEvent> T readEvent(ConsumerRecord<String, String> record, Class<T> eventClass,
                                                 String expectedType) {
        JsonNode payload;
        try {
            payload = objectMapper.readTree(record.value());
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed " + expectedType + " event", e);
        }
        if (payload == null || !payload.isObject() || !payload.hasNonNull("eventId")
                || !expectedType.equals(payload.path("eventType").asText(null))) {
            throw new IllegalArgumentException("Invalid " + expectedType + " event envelope");
        }

        T event;
        try {
            event = objectMapper.treeToValue(payload, eventClass);
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed " + expectedType + " event", e);
        }
        if (event == null || event.getEventId() == null || !expectedType.equals(event.getEventType())
                || event.getEventVersion() != 1) {
            throw new IllegalArgumentException("Invalid " + expectedType + " event envelope");
        }
        return event;
    }

}
