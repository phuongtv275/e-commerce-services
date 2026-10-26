package com.example.notificationservice.consumer;

import com.ecommerce.common.event.*;
import com.example.notificationservice.dto.internal.InternalOrderDto;
import com.example.notificationservice.dto.internal.InternalOrderItemDto;
import com.example.notificationservice.dto.internal.InternalUserDto;
import com.example.notificationservice.service.InternalClientService;
import com.example.notificationservice.service.NotificationIngestionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationKafkaConsumerTests {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Mock
    private NotificationIngestionService ingestionService;

    @Mock
    private InternalClientService internalClientService;

    @Mock
    private Acknowledgment acknowledgment;

    private NotificationKafkaConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new NotificationKafkaConsumer(
                objectMapper,
                ingestionService,
                internalClientService
        );
        ReflectionTestUtils.setField(consumer, "verificationBaseUrl", "http://localhost:3000/verify-email?token=");
    }

    @Test
    @DisplayName("Positive: Consume user.registered event and queue verification email")
    void testConsume_UserRegistered() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UserRegisteredEvent event = UserRegisteredEvent.builder()
                .eventId(eventId)
                .userId(userId)
                .email("user@example.com")
                .fullName("Nguyễn Văn A")
                .verificationToken("secret-token-xyz")
                .correlationId("corr-reg-1")
                .build();

        String payload = objectMapper.writeValueAsString(event);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("user.registered", 0, 10L, "key", payload);

        consumer.consume(record, acknowledgment);

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(ingestionService).processAndQueue(
                eq(eventId.toString()),
                eq("user.registered"),
                eq("corr-reg-1"),
                eq("user@example.com"),
                paramsCaptor.capture(),
                any()
        );

        Map<String, Object> params = paramsCaptor.getValue();
        assertEquals("Nguyễn Văn A", params.get("fullName"));
        assertEquals("http://localhost:3000/verify-email?token=secret-token-xyz", params.get("verificationUrl"));

        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("Positive: Consume order.status-changed with CONFIRMED status queues confirmation email with item rows")
    void testConsume_OrderStatusChanged_Confirmed() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        OrderStatusChangedEvent event = OrderStatusChangedEvent.builder()
                .eventId(eventId)
                .orderId(orderId)
                .orderCode("ORD-999")
                .oldStatus("PENDING")
                .newStatus("CONFIRMED")
                .correlationId("corr-order-conf")
                .build();

        InternalOrderItemDto item = InternalOrderItemDto.builder()
                .productName("Áo Polo")
                .quantity(2)
                .unitPrice(new BigDecimal("200000"))
                .lineTotal(new BigDecimal("400000"))
                .build();

        InternalOrderDto orderSnapshot = InternalOrderDto.builder()
                .orderId(orderId)
                .customerId(customerId)
                .orderCode("ORD-999")
                .status("CONFIRMED")
                .totalAmount(new BigDecimal("400000"))
                .currency("VND")
                .shippingAddress("123 Hà Nội")
                .items(List.of(item))
                .build();

        InternalUserDto user = InternalUserDto.builder()
                .id(customerId)
                .fullName("Khách hàng VIP")
                .email("vip@example.com")
                .build();

        when(internalClientService.getOrder(orderId)).thenReturn(orderSnapshot);
        when(internalClientService.getUser(customerId)).thenReturn(user);

        String payload = objectMapper.writeValueAsString(event);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("order.status-changed", 0, 11L, "key", payload);

        consumer.consume(record, acknowledgment);

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(ingestionService).processAndQueue(
                eq(eventId.toString()),
                eq("order.status-changed"),
                eq("corr-order-conf"),
                eq("vip@example.com"),
                paramsCaptor.capture(),
                any()
        );

        Map<String, Object> params = paramsCaptor.getValue();
        assertEquals("ORD-999", params.get("orderCode"));
        assertEquals("Khách hàng VIP", params.get("customerName"));
        assertTrue(params.get("orderItemsHtml").toString().contains("&Aacute;o Polo"));

        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("Positive: Consume order.status-changed with non-CONFIRMED status skips email and ACKs")
    void testConsume_OrderStatusChanged_NotConfirmed_Skipped() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        OrderStatusChangedEvent event = OrderStatusChangedEvent.builder()
                .eventId(eventId)
                .orderId(orderId)
                .orderCode("ORD-999")
                .oldStatus("CONFIRMED")
                .newStatus("SHIPPING")
                .build();

        String payload = objectMapper.writeValueAsString(event);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("order.status-changed", 0, 12L, "key", payload);

        consumer.consume(record, acknowledgment);

        verify(internalClientService, never()).getOrder(any());
        verify(ingestionService, never()).processAndQueue(any(), any(), any(), any(), any(), any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("Positive: Consume payment.succeeded event queues receipt email")
    void testConsume_PaymentSucceeded() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        PaymentSucceededEvent event = PaymentSucceededEvent.builder()
                .eventId(eventId)
                .orderId(orderId)
                .amount(new BigDecimal("150000"))
                .currency("VND")
                .providerPaymentId("VNPAY-123456")
                .paidAt(Instant.now())
                .correlationId("corr-pay-1")
                .build();

        InternalOrderDto orderSnapshot = InternalOrderDto.builder()
                .orderId(orderId)
                .customerId(customerId)
                .orderCode("ORD-PAY-1")
                .build();

        InternalUserDto user = InternalUserDto.builder()
                .id(customerId)
                .fullName("Người Mua")
                .email("buyer@example.com")
                .build();

        when(internalClientService.getOrder(orderId)).thenReturn(orderSnapshot);
        when(internalClientService.getUser(customerId)).thenReturn(user);

        String payload = objectMapper.writeValueAsString(event);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment.succeeded", 0, 13L, "key", payload);

        consumer.consume(record, acknowledgment);

        verify(ingestionService).processAndQueue(
                eq(eventId.toString()),
                eq("payment.succeeded"),
                eq("corr-pay-1"),
                eq("buyer@example.com"),
                any(),
                any()
        );
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("Negative: Internal lookup failure throws exception without ACK for retry")
    void testConsume_InternalLookupFailure_ThrowsException() {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        OrderStatusChangedEvent event = OrderStatusChangedEvent.builder()
                .eventId(eventId)
                .orderId(orderId)
                .newStatus("CONFIRMED")
                .build();

        when(internalClientService.getOrder(orderId)).thenThrow(new RuntimeException("Order service unavailable"));

        assertThrows(RuntimeException.class, () -> {
            String payload = objectMapper.writeValueAsString(event);
            ConsumerRecord<String, String> record = new ConsumerRecord<>("order.status-changed", 0, 14L, "key", payload);
            consumer.consume(record, acknowledgment);
        });

        verify(acknowledgment, never()).acknowledge();
        verify(ingestionService, never()).processAndQueue(any(), any(), any(), any(), any(), any());
    }
}
