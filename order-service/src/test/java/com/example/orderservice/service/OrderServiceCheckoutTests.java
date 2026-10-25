package com.example.orderservice.service;

import com.ecommerce.common.exception.ConflictException;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.ecommerce.common.exception.ValidationException;
import com.ecommerce.common.outbox.OutboxEventRepository;
import com.example.orderservice.client.CatalogClient;
import com.example.orderservice.client.IdentityClient;
import com.example.orderservice.client.InventoryClient;
import com.example.orderservice.client.dto.*;
import com.example.orderservice.dto.*;
import com.example.orderservice.entity.*;
import com.example.orderservice.repository.*;
import com.example.orderservice.service.OrderCheckoutTxManager.ClaimResult;
import com.example.orderservice.service.impl.OrderServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceCheckoutTests {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private OrderStatusHistoryRepository orderStatusHistoryRepository;

    @Mock
    private CartRepository cartRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private OrderCheckoutTxManager orderCheckoutTxManager;

    @Mock
    private CatalogClient catalogClient;

    @Mock
    private IdentityClient identityClient;

    @Mock
    private InventoryClient inventoryClient;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @InjectMocks
    private OrderServiceImpl orderService;

    private UUID customerId;
    private UUID addressId;
    private UUID skuId;
    private Cart cart;
    private String idempotencyKey;

    @BeforeEach
    void setUp() {
        customerId = UUID.randomUUID();
        addressId = UUID.randomUUID();
        skuId = UUID.randomUUID();
        idempotencyKey = UUID.randomUUID().toString();

        cart = Cart.builder()
                .id(UUID.randomUUID())
                .customerId(customerId)
                .version(1L)
                .items(new ArrayList<>())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("Positive: checkout succeeds, snapshots prices, reserves stock, commits order and clears cart")
    void testCheckout_Success() {
        CheckoutRequest req = new CheckoutRequest(addressId, "VNPAY");

        CartItem item = CartItem.builder()
                .id(UUID.randomUUID())
                .cart(cart)
                .skuId(skuId)
                .skuCode("SKU-100")
                .quantity(2)
                .unitPrice(new BigDecimal("50000"))
                .build();
        cart.getItems().add(item);

        UUID attemptOrderId = UUID.randomUUID();
        IdempotencyRecord record = IdempotencyRecord.builder()
                .id(UUID.randomUUID())
                .customerId(customerId)
                .operation("CHECKOUT")
                .idempotencyKey(idempotencyKey)
                .attemptOrderId(attemptOrderId)
                .status(IdempotencyStatus.IN_PROGRESS)
                .build();

        when(orderCheckoutTxManager.claimIdempotency(eq(customerId), eq(idempotencyKey), anyString(), anyInt()))
                .thenReturn(new ClaimResult(true, false, record, null));

        when(cartRepository.findByCustomerIdWithItems(customerId)).thenReturn(Optional.of(cart));

        IdentityAddressResponse address = IdentityAddressResponse.builder()
                .id(addressId)
                .userId(customerId)
                .recipientName("Nguyen Van A")
                .phone("0987654321")
                .addressLine1("123 Duong Le Loi")
                .district("Quan 1")
                .city("TP HCM")
                .country("Vietnam")
                .build();
        when(identityClient.getAddress(customerId, addressId)).thenReturn(address);

        CatalogSkuItemResponse catalogItem = CatalogSkuItemResponse.builder()
                .skuId(skuId)
                .skuCode("SKU-100")
                .productName("Áo Khoác")
                .skuName("Đen XL")
                .price(new BigDecimal("60000"))
                .status("ACTIVE")
                .build();
        when(catalogClient.getBatchSkus(List.of(skuId)))
                .thenReturn(new CatalogBatchSkuResponse(List.of(catalogItem), Collections.emptyList()));

        CheckoutResponse expectedResponse = CheckoutResponse.builder()
                .orderId(attemptOrderId)
                .orderCode("ORD-12345")
                .status(OrderStatus.PENDING)
                .totalAmount(new BigDecimal("120000"))
                .currency("VND")
                .createdAt(Instant.now())
                .build();

        when(orderCheckoutTxManager.commitCheckout(
                eq(customerId), eq(cart), anyList(), eq(address), eq(attemptOrderId), eq(idempotencyKey), eq(record.getId()), any()))
                .thenReturn(expectedResponse);

        CheckoutResponse response = orderService.checkout(customerId, idempotencyKey, req);

        assertNotNull(response);
        assertEquals(attemptOrderId, response.getOrderId());
        assertEquals(OrderStatus.PENDING, response.getStatus());
        verify(inventoryClient).reserve(eq(attemptOrderId), anyList());
        verify(orderCheckoutTxManager).commitCheckout(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Positive: customer cancel on PENDING order transitions atomically via CAS and emits events")
    void testCancelOrder_PendingOrder_Success() {
        UUID orderId = UUID.randomUUID();
        Order order = Order.builder()
                .id(orderId)
                .orderCode("ORD-001")
                .customerId(customerId)
                .status(OrderStatus.PENDING)
                .subtotal(new BigDecimal("100000"))
                .totalAmount(new BigDecimal("100000"))
                .currency("VND")
                .items(new ArrayList<>())
                .statusHistories(new ArrayList<>())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(orderRepository.findByIdAndCustomerId(orderId, customerId)).thenReturn(Optional.of(order));
        when(orderRepository.transitionOrderStatus(eq(orderId), eq(OrderStatus.PENDING), eq(OrderStatus.CANCELLED), any(), any(), any()))
                .thenReturn(1);
        when(orderRepository.findByIdAndCustomerIdWithDetails(orderId, customerId)).thenReturn(Optional.of(order));

        CustomerOrderDetailResponse response = orderService.cancelOrder(customerId, orderId, new CancelOrderRequest("Khong con nhu cau"));

        assertNotNull(response);
        verify(orderRepository).transitionOrderStatus(eq(orderId), eq(OrderStatus.PENDING), eq(OrderStatus.CANCELLED), any(), any(), any());
        verify(orderStatusHistoryRepository).save(any(OrderStatusHistory.class));
        verify(outboxEventRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("Negative: customer cancel CAS conflict (order changed to CONFIRMED) throws ConflictException")
    void testCancelOrder_CasConflictThrowsException() {
        UUID orderId = UUID.randomUUID();
        Order initialOrder = Order.builder()
                .id(orderId)
                .orderCode("ORD-001")
                .customerId(customerId)
                .status(OrderStatus.PENDING)
                .build();

        Order confirmedOrder = Order.builder()
                .id(orderId)
                .orderCode("ORD-001")
                .customerId(customerId)
                .status(OrderStatus.CONFIRMED) // Concurrently confirmed by payment
                .build();

        when(orderRepository.findByIdAndCustomerId(orderId, customerId))
                .thenReturn(Optional.of(initialOrder))
                .thenReturn(Optional.of(confirmedOrder));

        // CAS update fails because status is no longer PENDING
        when(orderRepository.transitionOrderStatus(eq(orderId), eq(OrderStatus.PENDING), eq(OrderStatus.CANCELLED), any(), any(), any()))
                .thenReturn(0);

        assertThrows(ConflictException.class, () ->
                orderService.cancelOrder(customerId, orderId, new CancelOrderRequest("Late cancel")));

        verify(orderStatusHistoryRepository, never()).save(any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("Positive: handlePaymentSucceeded transitions atomically with CAS=1")
    void testHandlePaymentSucceeded_CasSuccess() {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Order order = Order.builder()
                .id(orderId)
                .orderCode("ORD-001")
                .customerId(customerId)
                .status(OrderStatus.PENDING)
                .subtotal(new BigDecimal("100000"))
                .totalAmount(new BigDecimal("100000"))
                .currency("VND")
                .build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.transitionOrderStatus(eq(orderId), eq(OrderStatus.PENDING), eq(OrderStatus.CONFIRMED), any(), any(), any()))
                .thenReturn(1);

        orderService.handlePaymentSucceeded(orderId, paymentId);

        verify(orderRepository).transitionOrderStatus(eq(orderId), eq(OrderStatus.PENDING), eq(OrderStatus.CONFIRMED), any(), any(), any());
        verify(orderStatusHistoryRepository).save(any(OrderStatusHistory.class));
        verify(outboxEventRepository).save(any());
    }

    @Test
    @DisplayName("Negative: handlePaymentSucceeded CAS conflict (0 rows) does not write history or outbox")
    void testHandlePaymentSucceeded_CasConflict_NoHistoryOrOutbox() {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Order order = Order.builder()
                .id(orderId)
                .orderCode("ORD-001")
                .customerId(customerId)
                .status(OrderStatus.PENDING)
                .build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        // Concurrent transaction (e.g. customer cancel) won CAS, so this update returns 0
        when(orderRepository.transitionOrderStatus(eq(orderId), eq(OrderStatus.PENDING), eq(OrderStatus.CONFIRMED), any(), any(), any()))
                .thenReturn(0);

        orderService.handlePaymentSucceeded(orderId, paymentId);

        verify(orderStatusHistoryRepository, never()).save(any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("Negative: terminal state cannot regress on late payment.succeeded")
    void testStateTransition_TerminalStateCannotRegress() {
        UUID orderId = UUID.randomUUID();
        Order order = Order.builder()
                .id(orderId)
                .status(OrderStatus.CANCELLED)
                .build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        orderService.handlePaymentSucceeded(orderId, UUID.randomUUID());

        verify(orderRepository, never()).transitionOrderStatus(any(), any(), any(), any(), any(), any());
        verify(orderStatusHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Negative: local commit failure synchronously releases reservation and marks idempotency FAILED")
    void testCheckout_LocalCommitFailure_CompensatesReservation() {
        CheckoutRequest req = new CheckoutRequest(addressId, "VNPAY");

        CartItem item = CartItem.builder()
                .id(UUID.randomUUID())
                .cart(cart)
                .skuId(skuId)
                .skuCode("SKU-100")
                .quantity(1)
                .unitPrice(new BigDecimal("50000"))
                .build();
        cart.getItems().add(item);

        UUID attemptOrderId = UUID.randomUUID();
        IdempotencyRecord record = IdempotencyRecord.builder()
                .id(UUID.randomUUID())
                .customerId(customerId)
                .attemptOrderId(attemptOrderId)
                .status(IdempotencyStatus.IN_PROGRESS)
                .build();

        when(orderCheckoutTxManager.claimIdempotency(eq(customerId), eq(idempotencyKey), anyString(), anyInt()))
                .thenReturn(new ClaimResult(true, false, record, null));
        when(cartRepository.findByCustomerIdWithItems(customerId)).thenReturn(Optional.of(cart));
        when(identityClient.getAddress(customerId, addressId)).thenReturn(new IdentityAddressResponse());

        CatalogSkuItemResponse catalogItem = CatalogSkuItemResponse.builder()
                .skuId(skuId)
                .skuCode("SKU-100")
                .price(new BigDecimal("50000"))
                .status("ACTIVE")
                .build();
        when(catalogClient.getBatchSkus(List.of(skuId)))
                .thenReturn(new CatalogBatchSkuResponse(List.of(catalogItem), Collections.emptyList()));

        when(orderCheckoutTxManager.commitCheckout(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Simulated database failure during local commit"));

        assertThrows(RuntimeException.class, () -> orderService.checkout(customerId, idempotencyKey, req));

        // Verify synchronous compensation
        verify(inventoryClient).release(eq(attemptOrderId), contains("Order local commit failed"));
        // Verify idempotency marked as FAILED
        verify(orderCheckoutTxManager).markIdempotencyFailed(record.getId());
    }
}
