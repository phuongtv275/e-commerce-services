package com.example.notificationservice.consumer;

import com.ecommerce.common.event.*;
import com.example.notificationservice.dto.internal.InternalOrderDto;
import com.example.notificationservice.dto.internal.InternalOrderItemDto;
import com.example.notificationservice.dto.internal.InternalUserDto;
import com.example.notificationservice.service.InternalClientService;
import com.example.notificationservice.service.NotificationIngestionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationKafkaConsumer {

    private final ObjectMapper objectMapper;
    private final NotificationIngestionService ingestionService;
    private final InternalClientService internalClientService;

    @Value("${notification.verification.base-url:http://localhost:3000/verify-email}")
    private String verificationBaseUrl;

    @KafkaListener(
            topics = {
                    "#{'${notification.kafka.topics.user-registered:user.registered}'}",
                    "#{'${notification.kafka.topics.order-status-changed:order.status-changed}'}",
                    "#{'${notification.kafka.topics.order-cancelled:order.cancelled}'}",
                    "#{'${notification.kafka.topics.payment-succeeded:payment.succeeded}'}",
                    "#{'${notification.kafka.topics.payment-refund-completed:payment.refund-completed}'}",
                    "#{'${notification.kafka.topics.user-password-reset-requested:user.password-reset-requested}'}"
            },
            groupId = "${notification.kafka.consumer-group:${spring.kafka.consumer.group-id:notification-service-group}}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        String topic = record.topic();

        log.info("Received Kafka event on topic: {}, partition: {}, offset: {}",
                topic, record.partition(), record.offset());

        try {
            if (topic.endsWith("user.registered")) {
                handleUserRegistered(record.value(), record.partition(), record.offset());
            } else if (topic.endsWith("order.status-changed")) {
                handleOrderStatusChanged(record.value(), record.partition(), record.offset());
            } else if (topic.endsWith("order.cancelled")) {
                handleOrderCancelled(record.value(), record.partition(), record.offset());
            } else if (topic.endsWith("payment.succeeded")) {
                handlePaymentSucceeded(record.value(), record.partition(), record.offset());
            } else if (topic.endsWith("payment.refund-completed")) {
                handleRefundCompleted(record.value(), record.partition(), record.offset());
            } else if (topic.endsWith("user.password-reset-requested")) {
                handleUserPasswordResetRequested(record.value(), record.partition(), record.offset());
            } else {
                log.warn("Ignoring unexpected topic: {}", topic);
            }

            if (acknowledgment != null) {
                acknowledgment.acknowledge();
            }
        } catch (Exception e) {
            log.error("Failed to process event from topic {} (partition: {}, offset: {}): {}",
                    topic, record.partition(), record.offset(), e.getMessage());
            throw new RuntimeException("Kafka message processing failed for topic " + topic, e);
        }
    }

    private void handleUserRegistered(String rawPayload, int partition, long offset) throws Exception {
        UserRegisteredEvent event = objectMapper.readValue(rawPayload, UserRegisteredEvent.class);
        if (event.getEventId() == null || event.getEmail() == null || event.getEmail().isBlank()) {
            log.warn("UserRegisteredEvent missing required eventId or email at partition: {}, offset: {}", partition, offset);
            return;
        }

        String verificationLink = buildVerificationLink(event.getVerificationToken());

        Map<String, Object> params = new HashMap<>();
        params.put("fullName", event.getFullName() != null ? event.getFullName() : "Quý khách");
        params.put("verificationUrl", verificationLink);

        ingestionService.processAndQueue(
                event.getEventId().toString(),
                "user.registered",
                event.getCorrelationId(),
                event.getEmail(),
                params,
                event
        );
    }

    private void handleUserPasswordResetRequested(String rawPayload, int partition, long offset) throws Exception {
        UserPasswordResetRequestedEvent event = objectMapper.readValue(rawPayload, UserPasswordResetRequestedEvent.class);
        if (event.eventId() == null || event.email() == null || event.email().isBlank()) {
            log.warn("UserPasswordResetRequestedEvent missing required eventId or email at partition: {}, offset: {}", partition, offset);
            return;
        }

        Map<String, Object> params = new HashMap<>();
        params.put("fullName", event.fullName() != null ? event.fullName() : "Quý khách");
        params.put("resetToken", event.resetToken() != null ? event.resetToken() : "");

        ingestionService.processAndQueue(
                event.eventId().toString(),
                "user.password-reset-requested",
                event.correlationId(),
                event.email(),
                params,
                event
        );
    }

    private void handleOrderStatusChanged(String rawPayload, int partition, long offset) throws Exception {
        OrderStatusChangedEvent event = objectMapper.readValue(rawPayload, OrderStatusChangedEvent.class);
        if (event.getEventId() == null || event.getOrderId() == null) {
            log.warn("OrderStatusChangedEvent missing required fields at partition: {}, offset: {}", partition, offset);
            return;
        }

        // Only CONFIRMED orders trigger confirmation emails
        if (!"CONFIRMED".equalsIgnoreCase(event.getNewStatus())) {
            log.info("OrderStatusChangedEvent status is not CONFIRMED, skipping notification");
            return;
        }

        InternalOrderDto order = internalClientService.getOrder(event.getOrderId());
        if (order == null || order.getCustomerId() == null) {
            throw new IllegalStateException("Order snapshot or customerId not found for orderId: " + event.getOrderId());
        }

        InternalUserDto user = internalClientService.getUser(order.getCustomerId());
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            throw new IllegalStateException("User email not found for customerId: " + order.getCustomerId());
        }

        String orderItemsHtml = renderOrderItemsHtml(order.getItems(), order.getCurrency());

        Map<String, Object> params = new HashMap<>();
        params.put("customerName", user.getFullName() != null ? user.getFullName() : "Quý khách");
        params.put("orderCode", event.getOrderCode() != null ? event.getOrderCode() : (order.getOrderCode() != null ? order.getOrderCode() : event.getOrderId().toString()));
        params.put("totalAmount", order.getTotalAmount() != null ? order.getTotalAmount().toPlainString() : "0");
        params.put("currency", order.getCurrency() != null ? order.getCurrency() : "VND");
        params.put("shippingAddress", order.getShippingAddress() != null ? order.getShippingAddress() : "Theo thông tin đăng ký");
        params.put("orderItemsHtml", orderItemsHtml);

        ingestionService.processAndQueue(
                event.getEventId().toString(),
                "order.status-changed",
                event.getCorrelationId(),
                user.getEmail(),
                params,
                event
        );
    }

    private void handleOrderCancelled(String rawPayload, int partition, long offset) throws Exception {
        OrderCancelledEvent event = objectMapper.readValue(rawPayload, OrderCancelledEvent.class);
        if (event.getEventId() == null || event.getOrderId() == null) {
            log.warn("OrderCancelledEvent missing required fields at partition: {}, offset: {}", partition, offset);
            return;
        }

        InternalOrderDto order = internalClientService.getOrder(event.getOrderId());
        if (order == null || order.getCustomerId() == null) {
            throw new IllegalStateException("Order snapshot or customerId not found for orderId: " + event.getOrderId());
        }

        InternalUserDto user = internalClientService.getUser(order.getCustomerId());
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            throw new IllegalStateException("User email not found for customerId: " + order.getCustomerId());
        }

        Map<String, Object> params = new HashMap<>();
        params.put("customerName", user.getFullName() != null ? user.getFullName() : "Quý khách");
        params.put("orderCode", order.getOrderCode() != null ? order.getOrderCode() : event.getOrderId().toString());
        params.put("reason", event.getReason() != null ? event.getReason() : "Theo yêu cầu hệ thống");
        params.put("refundNotice", event.isRefundRequired()
                ? "Yêu cầu hoàn tiền của bạn đang được hệ thống xử lý."
                : "Đơn hàng chưa phát sinh chi phí hoặc không yêu cầu hoàn tiền.");

        ingestionService.processAndQueue(
                event.getEventId().toString(),
                "order.cancelled",
                event.getCorrelationId(),
                user.getEmail(),
                params,
                event
        );
    }

    private void handlePaymentSucceeded(String rawPayload, int partition, long offset) throws Exception {
        PaymentSucceededEvent event = objectMapper.readValue(rawPayload, PaymentSucceededEvent.class);
        if (event.getEventId() == null || event.getOrderId() == null) {
            log.warn("PaymentSucceededEvent missing required fields at partition: {}, offset: {}", partition, offset);
            return;
        }

        InternalOrderDto order = internalClientService.getOrder(event.getOrderId());
        if (order == null || order.getCustomerId() == null) {
            throw new IllegalStateException("Order snapshot or customerId not found for orderId: " + event.getOrderId());
        }

        InternalUserDto user = internalClientService.getUser(order.getCustomerId());
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            throw new IllegalStateException("User email not found for customerId: " + order.getCustomerId());
        }

        Map<String, Object> params = new HashMap<>();
        params.put("customerName", user.getFullName() != null ? user.getFullName() : "Quý khách");
        params.put("orderCode", order.getOrderCode() != null ? order.getOrderCode() : event.getOrderId().toString());
        params.put("amount", event.getAmount() != null ? event.getAmount().toPlainString() : "0");
        params.put("currency", event.getCurrency() != null ? event.getCurrency() : "VND");
        params.put("providerPaymentId", event.getProviderPaymentId() != null ? event.getProviderPaymentId() : "VNPAY");
        params.put("paidAt", event.getPaidAt() != null ? event.getPaidAt().toString() : "");

        ingestionService.processAndQueue(
                event.getEventId().toString(),
                "payment.succeeded",
                event.getCorrelationId(),
                user.getEmail(),
                params,
                event
        );
    }

    private void handleRefundCompleted(String rawPayload, int partition, long offset) throws Exception {
        RefundCompletedEvent event = objectMapper.readValue(rawPayload, RefundCompletedEvent.class);
        if (event.getEventId() == null || event.getOrderId() == null) {
            log.warn("RefundCompletedEvent missing required fields at partition: {}, offset: {}", partition, offset);
            return;
        }

        InternalOrderDto order = internalClientService.getOrder(event.getOrderId());
        if (order == null || order.getCustomerId() == null) {
            throw new IllegalStateException("Order snapshot or customerId not found for orderId: " + event.getOrderId());
        }

        InternalUserDto user = internalClientService.getUser(order.getCustomerId());
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            throw new IllegalStateException("User email not found for customerId: " + order.getCustomerId());
        }

        Map<String, Object> params = new HashMap<>();
        params.put("customerName", user.getFullName() != null ? user.getFullName() : "Quý khách");
        params.put("orderCode", order.getOrderCode() != null ? order.getOrderCode() : event.getOrderId().toString());
        params.put("amount", event.getAmount() != null ? event.getAmount().toPlainString() : "0");
        params.put("currency", event.getCurrency() != null ? event.getCurrency() : "VND");
        params.put("providerRefundId", event.getProviderRefundId() != null ? event.getProviderRefundId() : "");

        ingestionService.processAndQueue(
                event.getEventId().toString(),
                "payment.refund-completed",
                event.getCorrelationId(),
                user.getEmail(),
                params,
                event
        );
    }

    private String buildVerificationLink(String token) {
        if (token == null || token.isBlank()) {
            return verificationBaseUrl;
        }
        if (verificationBaseUrl.contains("?")) {
            return verificationBaseUrl.endsWith("=")
                    ? verificationBaseUrl + token
                    : verificationBaseUrl + "&token=" + token;
        }
        return verificationBaseUrl + "?token=" + token;
    }

    private String renderOrderItemsHtml(List<InternalOrderItemDto> items, String currency) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        String safeCurrency = currency != null ? HtmlUtils.htmlEscape(currency) : "VND";
        StringBuilder sb = new StringBuilder();
        sb.append("<table style=\"width:100%; border-collapse:collapse; margin-top:10px;\">");
        sb.append("<thead><tr style=\"background-color:#f2f2f2;\">");
        sb.append("<th style=\"border:1px solid #ddd; padding:8px; text-align:left;\">Sản phẩm</th>");
        sb.append("<th style=\"border:1px solid #ddd; padding:8px; text-align:center;\">Số lượng</th>");
        sb.append("<th style=\"border:1px solid #ddd; padding:8px; text-align:right;\">Đơn giá</th>");
        sb.append("<th style=\"border:1px solid #ddd; padding:8px; text-align:right;\">Thành tiền</th>");
        sb.append("</tr></thead><tbody>");

        for (InternalOrderItemDto item : items) {
            String name = item.getProductName() != null ? HtmlUtils.htmlEscape(item.getProductName()) : "";
            int qty = item.getQuantity() != null ? item.getQuantity() : 1;
            String unitPrice = item.getUnitPrice() != null ? item.getUnitPrice().toPlainString() : "0";
            String lineTotal = item.getLineTotal() != null ? item.getLineTotal().toPlainString() : "0";

            sb.append("<tr>");
            sb.append("<td style=\"border:1px solid #ddd; padding:8px;\">").append(name).append("</td>");
            sb.append("<td style=\"border:1px solid #ddd; padding:8px; text-align:center;\">").append(qty).append("</td>");
            sb.append("<td style=\"border:1px solid #ddd; padding:8px; text-align:right;\">").append(unitPrice).append(" ").append(safeCurrency).append("</td>");
            sb.append("<td style=\"border:1px solid #ddd; padding:8px; text-align:right;\">").append(lineTotal).append(" ").append(safeCurrency).append("</td>");
            sb.append("</tr>");
        }

        sb.append("</tbody></table>");
        return sb.toString();
    }
}
