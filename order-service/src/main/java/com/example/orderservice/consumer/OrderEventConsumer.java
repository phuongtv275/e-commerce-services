package com.example.orderservice.consumer;

import com.ecommerce.common.constant.SecurityConstants;
import com.ecommerce.common.event.PaymentFailedEvent;
import com.ecommerce.common.event.PaymentSucceededEvent;
import com.ecommerce.common.event.ReservationCommitFailedEvent;
import com.ecommerce.common.event.ReservationExpiredEvent;
import com.ecommerce.common.idempotency.IdempotentConsumerHelper;
import com.example.orderservice.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    private final OrderService orderService;
    private final IdempotentConsumerHelper idempotentConsumerHelper;
    private final ObjectMapper objectMapper;

    @Value("${order.kafka.consumer-group:order-service-group}")
    private String consumerGroup;

    @KafkaListener(
            topics = "${order.kafka.topic.payment-succeeded:payment.succeeded}",
            groupId = "${order.kafka.consumer-group:order-service-group}"
    )
    public void consumePaymentSucceeded(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            PaymentSucceededEvent event = objectMapper.readValue(record.value(), PaymentSucceededEvent.class);
            if (event.getOrderId() == null) {
                log.warn("payment.succeeded missing orderId: {}", record.value());
                if (ack != null) ack.acknowledge();
                return;
            }

            String eventId = event.getEventId() != null ? event.getEventId().toString() : record.key();
            if (event.getCorrelationId() != null) {
                MDC.put(SecurityConstants.MDC_CORRELATION_ID, event.getCorrelationId());
            }

            idempotentConsumerHelper.processIdempotent(eventId, consumerGroup, event, evt -> {
                orderService.handlePaymentSucceeded(evt.getOrderId(), evt.getPaymentId());
            });

            if (ack != null) ack.acknowledge();

        } catch (Exception e) {
            log.error("Failed to process payment.succeeded message: {}", record.value(), e);
            throw new RuntimeException("Error processing payment.succeeded", e);
        } finally {
            MDC.remove(SecurityConstants.MDC_CORRELATION_ID);
        }
    }

    @KafkaListener(
            topics = "${order.kafka.topic.payment-failed:payment.failed}",
            groupId = "${order.kafka.consumer-group:order-service-group}"
    )
    public void consumePaymentFailed(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            PaymentFailedEvent event = objectMapper.readValue(record.value(), PaymentFailedEvent.class);
            if (event.getOrderId() == null) {
                log.warn("payment.failed missing orderId: {}", record.value());
                if (ack != null) ack.acknowledge();
                return;
            }

            String eventId = event.getEventId() != null ? event.getEventId().toString() : record.key();
            if (event.getCorrelationId() != null) {
                MDC.put(SecurityConstants.MDC_CORRELATION_ID, event.getCorrelationId());
            }

            idempotentConsumerHelper.processIdempotent(eventId, consumerGroup, event, evt -> {
                orderService.handlePaymentFailed(evt.getOrderId(), evt.getFailureReason());
            });

            if (ack != null) ack.acknowledge();

        } catch (Exception e) {
            log.error("Failed to process payment.failed message: {}", record.value(), e);
            throw new RuntimeException("Error processing payment.failed", e);
        } finally {
            MDC.remove(SecurityConstants.MDC_CORRELATION_ID);
        }
    }

    @KafkaListener(
            topics = "${order.kafka.topic.reservation-expired:inventory.reservation-expired}",
            groupId = "${order.kafka.consumer-group:order-service-group}"
    )
    public void consumeReservationExpired(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            ReservationExpiredEvent event = objectMapper.readValue(record.value(), ReservationExpiredEvent.class);
            if (event.getOrderId() == null) {
                log.warn("inventory.reservation-expired missing orderId: {}", record.value());
                if (ack != null) ack.acknowledge();
                return;
            }

            String eventId = event.getEventId() != null ? event.getEventId().toString() : record.key();
            if (event.getCorrelationId() != null) {
                MDC.put(SecurityConstants.MDC_CORRELATION_ID, event.getCorrelationId());
            }

            idempotentConsumerHelper.processIdempotent(eventId, consumerGroup, event, evt -> {
                orderService.handleReservationExpired(evt.getOrderId());
            });

            if (ack != null) ack.acknowledge();

        } catch (Exception e) {
            log.error("Failed to process inventory.reservation-expired message: {}", record.value(), e);
            throw new RuntimeException("Error processing inventory.reservation-expired", e);
        } finally {
            MDC.remove(SecurityConstants.MDC_CORRELATION_ID);
        }
    }

    @KafkaListener(
            topics = "${order.kafka.topic.reservation-commit-failed:inventory.reservation-commit-failed}",
            groupId = "${order.kafka.consumer-group:order-service-group}"
    )
    public void consumeReservationCommitFailed(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            ReservationCommitFailedEvent event = objectMapper.readValue(record.value(), ReservationCommitFailedEvent.class);
            if (event.getOrderId() == null) {
                log.warn("inventory.reservation-commit-failed missing orderId: {}", record.value());
                if (ack != null) ack.acknowledge();
                return;
            }

            String eventId = event.getEventId() != null ? event.getEventId().toString() : record.key();
            if (event.getCorrelationId() != null) {
                MDC.put(SecurityConstants.MDC_CORRELATION_ID, event.getCorrelationId());
            }

            idempotentConsumerHelper.processIdempotent(eventId, consumerGroup, event, evt -> {
                orderService.handleReservationCommitFailed(evt.getOrderId(), evt.getReason());
            });

            if (ack != null) ack.acknowledge();

        } catch (Exception e) {
            log.error("Failed to process inventory.reservation-commit-failed message: {}", record.value(), e);
            throw new RuntimeException("Error processing inventory.reservation-commit-failed", e);
        } finally {
            MDC.remove(SecurityConstants.MDC_CORRELATION_ID);
        }
    }
}
