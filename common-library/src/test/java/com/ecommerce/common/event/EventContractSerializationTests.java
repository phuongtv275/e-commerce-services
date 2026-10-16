package com.ecommerce.common.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EventContractSerializationTests {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    @DisplayName("Positive: Serialize & Deserialize OrderCreatedEvent bảo toàn đầy đủ dữ liệu")
    void testOrderCreatedEventSerialization_Positive() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();

        OrderCreatedEvent event = OrderCreatedEvent.builder()
                .orderId(orderId)
                .customerId(customerId)
                .orderCode("ORD-2026-999")
                .totalAmount(new BigDecimal("250000.00"))
                .currency("VND")
                .correlationId("test-corr-order-1")
                .reservationExpiresAt(Instant.now().plusSeconds(900))
                .items(List.of(
                        OrderItemPayload.builder()
                                .skuId(skuId)
                                .skuCode("SKU-LAPTOP-01")
                                .quantity(1)
                                .unitPrice(new BigDecimal("250000.00"))
                                .build()
                ))
                .build();

        String json = objectMapper.writeValueAsString(event);
        assertNotNull(json);

        OrderCreatedEvent deserialized = objectMapper.readValue(json, OrderCreatedEvent.class);
        assertEquals(event.getEventId(), deserialized.getEventId());
        assertEquals("order.created", deserialized.getEventType());
        assertEquals(orderId, deserialized.getOrderId());
        assertEquals("ORD-2026-999", deserialized.getOrderCode());
        assertEquals(1, deserialized.getItems().size());
        assertEquals("SKU-LAPTOP-01", deserialized.getItems().get(0).getSkuCode());
        assertEquals("order-service", deserialized.getProducer());
    }

    @Test
    @DisplayName("Positive: Serialize & Deserialize PaymentLateSuccessEvent với lý do late payment")
    void testPaymentLateSuccessEventSerialization_Positive() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        PaymentLateSuccessEvent event = PaymentLateSuccessEvent.builder()
                .paymentId(paymentId)
                .orderId(orderId)
                .amount(new BigDecimal("150000.00"))
                .currency("VND")
                .providerPaymentId("VNPAY-TRANS-888")
                .lateSuccessReason("RESERVATION_EXPIRED_ORDER_CANCELLED")
                .correlationId("corr-late-pay")
                .build();

        String json = objectMapper.writeValueAsString(event);
        PaymentLateSuccessEvent deserialized = objectMapper.readValue(json, PaymentLateSuccessEvent.class);

        assertEquals("payment.late-success", deserialized.getEventType());
        assertEquals("RESERVATION_EXPIRED_ORDER_CANCELLED", deserialized.getLateSuccessReason());
        assertEquals("VNPAY-TRANS-888", deserialized.getProviderPaymentId());
    }
}
