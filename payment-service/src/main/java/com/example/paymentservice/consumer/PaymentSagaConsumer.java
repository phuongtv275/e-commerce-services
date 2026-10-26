package com.example.paymentservice.consumer;

import com.ecommerce.common.event.OrderCancelledEvent;
import com.ecommerce.common.event.PaymentLateSuccessEvent;
import com.ecommerce.common.event.ReservationCommitFailedEvent;
import com.ecommerce.common.event.ReservationExpiredEvent;
import com.ecommerce.common.idempotency.IdempotentConsumerHelper;
import com.example.paymentservice.entity.Payment;
import com.example.paymentservice.entity.PaymentAuditLog;
import com.example.paymentservice.entity.PaymentStatus;
import com.example.paymentservice.refund.service.RefundService;
import com.example.paymentservice.repository.PaymentAuditLogRepository;
import com.example.paymentservice.repository.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
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
        PaymentLateSuccessEvent event;
        try {
            event = objectMapper.readValue(record.value(), PaymentLateSuccessEvent.class);
        } catch (Exception e) {
            log.error("Failed to parse PaymentLateSuccessEvent: {}", e.getMessage());
            if (ack != null) ack.acknowledge();
            return;
        }

        if (event == null || event.getOrderId() == null) {
            if (ack != null) ack.acknowledge();
            return;
        }

        String eventId = event.getEventId() != null ? event.getEventId().toString() : record.key();
        idempotentConsumerHelper.processIdempotent(eventId, consumerGroup, event, evt ->
                refundService.processAutoRefund(evt.getOrderId(), null,
                        "Late payment success: " + evt.getLateSuccessReason(), evt.getCorrelationId()));

        if (ack != null) ack.acknowledge();
    }

    @KafkaListener(
            topics = "${payment.kafka.topic.order-cancelled:order.cancelled}",
            groupId = "${payment.kafka.consumer-group:payment-service-group}"
    )
    public void consumeOrderCancelled(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.info("Received order.cancelled record key={}", record.key());
        OrderCancelledEvent event;
        try {
            event = objectMapper.readValue(record.value(), OrderCancelledEvent.class);
        } catch (Exception e) {
            log.error("Failed to parse OrderCancelledEvent: {}", e.getMessage());
            if (ack != null) ack.acknowledge();
            return;
        }

        if (event == null || event.getOrderId() == null) {
            if (ack != null) ack.acknowledge();
            return;
        }

        if (!event.isRefundRequired()) {
            log.info("order.cancelled for orderId={} does not require refund. No-op.", event.getOrderId());
            if (ack != null) ack.acknowledge();
            return;
        }

        String eventId = event.getEventId() != null ? event.getEventId().toString() : record.key();
        idempotentConsumerHelper.processIdempotent(eventId, consumerGroup, event, evt -> {
            refundService.processAutoRefund(evt.getOrderId(), null, evt.getReason(), evt.getCorrelationId());
        });

        if (ack != null) {
            ack.acknowledge();
        }
    }

    @KafkaListener(
            topics = "${payment.kafka.topic.reservation-commit-failed:inventory.reservation-commit-failed}",
            groupId = "${payment.kafka.consumer-group:payment-service-group}"
    )
    public void consumeReservationCommitFailed(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.info("Received inventory.reservation-commit-failed record key={}", record.key());
        ReservationCommitFailedEvent event;
        try {
            event = objectMapper.readValue(record.value(), ReservationCommitFailedEvent.class);
        } catch (Exception e) {
            log.error("Failed to parse ReservationCommitFailedEvent: {}", e.getMessage());
            if (ack != null) ack.acknowledge();
            return;
        }

        if (event == null || event.getOrderId() == null) {
            if (ack != null) ack.acknowledge();
            return;
        }

        String eventId = event.getEventId() != null ? event.getEventId().toString() : record.key();
        idempotentConsumerHelper.processIdempotent(eventId, consumerGroup, event, evt -> {
            refundService.processAutoRefund(evt.getOrderId(), null,
                    "Reservation commit failed: " + evt.getReason(), evt.getCorrelationId());
        });

        if (ack != null) {
            ack.acknowledge();
        }
    }

    @KafkaListener(
            topics = "${payment.kafka.topic.reservation-expired:inventory.reservation-expired}",
            groupId = "${payment.kafka.consumer-group:payment-service-group}"
    )
    public void consumeReservationExpired(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.info("Received inventory.reservation-expired record key={}", record.key());
        ReservationExpiredEvent event;
        try {
            event = objectMapper.readValue(record.value(), ReservationExpiredEvent.class);
        } catch (Exception e) {
            log.error("Failed to parse ReservationExpiredEvent: {}", e.getMessage());
            if (ack != null) ack.acknowledge();
            return;
        }

        if (event == null || event.getOrderId() == null) {
            if (ack != null) ack.acknowledge();
            return;
        }

        String eventId = event.getEventId() != null ? event.getEventId().toString() : record.key();
        idempotentConsumerHelper.processIdempotent(eventId, consumerGroup, event, evt -> {
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

        if (ack != null) {
            ack.acknowledge();
        }
    }
}
