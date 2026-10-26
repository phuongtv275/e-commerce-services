package com.example.orderservice.service;

import com.ecommerce.common.event.OrderCancelledEvent;
import com.ecommerce.common.event.OrderStatusChangedEvent;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.ecommerce.common.outbox.OutboxEvent;
import com.ecommerce.common.outbox.OutboxEventRepository;
import com.example.orderservice.entity.Order;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.entity.OrderStatusHistory;
import com.example.orderservice.repository.OrderRepository;
import com.example.orderservice.repository.OrderStatusHistoryRepository;
import com.example.orderservice.service.impl.OrderServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceReservationCommitFailedTests {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderStatusHistoryRepository orderStatusHistoryRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @InjectMocks
    private OrderServiceImpl orderService;

    private UUID orderId;
    private UUID customerId;

    @BeforeEach
    void setUp() {
        orderId = UUID.randomUUID();
        customerId = UUID.randomUUID();
    }

    private Order createOrder(OrderStatus status) {
        return Order.builder()
                .id(orderId)
                .orderCode("ORD-TEST-001")
                .customerId(customerId)
                .status(status)
                .totalAmount(new BigDecimal("100000"))
                .currency("VND")
                .paymentMethod("VNPAY")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("Positive: PENDING order on commit-failed transitions to CANCELLED with refundRequired=false")
    void testCommitFailed_WhenPending_TransitionsToCancelled_RefundRequiredFalse() throws Exception {
        Order pendingOrder = createOrder(OrderStatus.PENDING);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(pendingOrder));
        when(orderRepository.transitionOrderStatus(eq(orderId), eq(OrderStatus.PENDING), eq(OrderStatus.CANCELLED),
                any(), any(), any())).thenReturn(1);
        when(orderRepository.getReferenceById(orderId)).thenReturn(pendingOrder);

        orderService.handleReservationCommitFailed(orderId, "Item out of stock");

        // Verify CAS update
        verify(orderRepository).transitionOrderStatus(eq(orderId), eq(OrderStatus.PENDING), eq(OrderStatus.CANCELLED),
                contains("Item out of stock"), any(Instant.class), any(Instant.class));

        // Verify status history
        ArgumentCaptor<OrderStatusHistory> historyCaptor = ArgumentCaptor.forClass(OrderStatusHistory.class);
        verify(orderStatusHistoryRepository).save(historyCaptor.capture());
        OrderStatusHistory history = historyCaptor.getValue();
        assertThat(history.getOldStatus()).isEqualTo(OrderStatus.PENDING.name());
        assertThat(history.getNewStatus()).isEqualTo(OrderStatus.CANCELLED.name());
        assertThat(history.getActor()).isEqualTo("inventory-service");
        assertThat(history.getReason()).contains("Item out of stock");

        // Verify outbox events
        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository, times(2)).save(outboxCaptor.capture());
        List<OutboxEvent> savedEvents = outboxCaptor.getAllValues();

        OutboxEvent statusEventRecord = savedEvents.stream()
                .filter(e -> "order.status-changed".equals(e.getEventType()))
                .findFirst().orElseThrow();
        OrderStatusChangedEvent statusEvent = objectMapper.readValue(statusEventRecord.getPayload(), OrderStatusChangedEvent.class);
        assertThat(statusEvent.getOldStatus()).isEqualTo(OrderStatus.PENDING.name());
        assertThat(statusEvent.getNewStatus()).isEqualTo(OrderStatus.CANCELLED.name());
        assertThat(statusEvent.getOrderId()).isEqualTo(orderId);

        OutboxEvent cancelEventRecord = savedEvents.stream()
                .filter(e -> "order.cancelled".equals(e.getEventType()))
                .findFirst().orElseThrow();
        OrderCancelledEvent cancelEvent = objectMapper.readValue(cancelEventRecord.getPayload(), OrderCancelledEvent.class);
        assertThat(cancelEvent.getOrderId()).isEqualTo(orderId);
        assertThat(cancelEvent.isRefundRequired()).isFalse();
        assertThat(cancelEvent.getCancelledBy()).isEqualTo("inventory-service");
        assertThat(cancelEvent.getReason()).contains("Item out of stock");
    }

    @Test
    @DisplayName("Positive: CONFIRMED order on commit-failed transitions to CANCELLED with refundRequired=true")
    void testCommitFailed_WhenConfirmed_TransitionsToCancelled_RefundRequiredTrue() throws Exception {
        Order confirmedOrder = createOrder(OrderStatus.CONFIRMED);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(confirmedOrder));
        when(orderRepository.transitionOrderStatus(eq(orderId), eq(OrderStatus.CONFIRMED), eq(OrderStatus.CANCELLED),
                any(), any(), any())).thenReturn(1);
        when(orderRepository.getReferenceById(orderId)).thenReturn(confirmedOrder);

        orderService.handleReservationCommitFailed(orderId, "Inventory commit failed after payment");

        // Verify CAS update with expected status CONFIRMED
        verify(orderRepository).transitionOrderStatus(eq(orderId), eq(OrderStatus.CONFIRMED), eq(OrderStatus.CANCELLED),
                contains("Inventory commit failed after payment"), any(Instant.class), any(Instant.class));

        // Verify status history
        ArgumentCaptor<OrderStatusHistory> historyCaptor = ArgumentCaptor.forClass(OrderStatusHistory.class);
        verify(orderStatusHistoryRepository).save(historyCaptor.capture());
        OrderStatusHistory history = historyCaptor.getValue();
        assertThat(history.getOldStatus()).isEqualTo(OrderStatus.CONFIRMED.name());
        assertThat(history.getNewStatus()).isEqualTo(OrderStatus.CANCELLED.name());
        assertThat(history.getActor()).isEqualTo("inventory-service");
        assertThat(history.getReason()).contains("Inventory commit failed after payment");

        // Verify outbox events
        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository, times(2)).save(outboxCaptor.capture());
        List<OutboxEvent> savedEvents = outboxCaptor.getAllValues();

        OutboxEvent statusEventRecord = savedEvents.stream()
                .filter(e -> "order.status-changed".equals(e.getEventType()))
                .findFirst().orElseThrow();
        OrderStatusChangedEvent statusEvent = objectMapper.readValue(statusEventRecord.getPayload(), OrderStatusChangedEvent.class);
        assertThat(statusEvent.getOldStatus()).isEqualTo(OrderStatus.CONFIRMED.name());
        assertThat(statusEvent.getNewStatus()).isEqualTo(OrderStatus.CANCELLED.name());

        OutboxEvent cancelEventRecord = savedEvents.stream()
                .filter(e -> "order.cancelled".equals(e.getEventType()))
                .findFirst().orElseThrow();
        OrderCancelledEvent cancelEvent = objectMapper.readValue(cancelEventRecord.getPayload(), OrderCancelledEvent.class);
        assertThat(cancelEvent.getOrderId()).isEqualTo(orderId);
        assertThat(cancelEvent.isRefundRequired()).isTrue();
        assertThat(cancelEvent.getCancelledBy()).isEqualTo("inventory-service");
        assertThat(cancelEvent.getReason()).contains("Inventory commit failed after payment");
    }

    @Test
    @DisplayName("Negative: Duplicate delivery when already CANCELLED is a no-op")
    void testCommitFailed_WhenAlreadyCancelled_IsNoOp() {
        Order cancelledOrder = createOrder(OrderStatus.CANCELLED);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(cancelledOrder));

        orderService.handleReservationCommitFailed(orderId, "Duplicate commit-failed event");

        verify(orderRepository, never()).transitionOrderStatus(any(), any(), any(), any(), any(), any());
        verify(orderStatusHistoryRepository, never()).save(any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("Negative: Terminal status COMPLETED cannot regress and is a no-op")
    void testCommitFailed_WhenCompleted_IsNoOp() {
        Order completedOrder = createOrder(OrderStatus.COMPLETED);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(completedOrder));

        orderService.handleReservationCommitFailed(orderId, "Late commit-failed event");

        verify(orderRepository, never()).transitionOrderStatus(any(), any(), any(), any(), any(), any());
        verify(orderStatusHistoryRepository, never()).save(any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("Negative: Race condition where CAS update affects 0 rows writes neither history nor outbox")
    void testCommitFailed_WhenCasFails_DoesNotWriteHistoryOrOutbox() {
        Order confirmedOrder = createOrder(OrderStatus.CONFIRMED);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(confirmedOrder));
        when(orderRepository.transitionOrderStatus(eq(orderId), eq(OrderStatus.CONFIRMED), eq(OrderStatus.CANCELLED),
                any(), any(), any())).thenReturn(0);

        orderService.handleReservationCommitFailed(orderId, "Concurrent modification race");

        verify(orderStatusHistoryRepository, never()).save(any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("Negative: Missing order throws so the commit-failed event can retry to DLT")
    void testCommitFailed_WhenOrderNotFound_IsRetried() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.handleReservationCommitFailed(orderId, "Missing order commit failed"))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(orderRepository, never()).transitionOrderStatus(any(), any(), any(), any(), any(), any());
        verify(orderStatusHistoryRepository, never()).save(any());
        verify(outboxEventRepository, never()).save(any());
    }
}
