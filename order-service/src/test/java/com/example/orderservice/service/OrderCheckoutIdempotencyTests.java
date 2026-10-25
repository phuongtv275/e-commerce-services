package com.example.orderservice.service;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.exception.ConflictException;
import com.ecommerce.common.outbox.OutboxEventRepository;
import com.example.orderservice.client.dto.IdentityAddressResponse;
import com.example.orderservice.dto.CheckoutResponse;
import com.example.orderservice.entity.*;
import com.example.orderservice.repository.*;
import com.example.orderservice.service.OrderCheckoutTxManager.ClaimResult;
import com.example.orderservice.service.OrderCheckoutTxManager.OrderItemSnapshot;
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
class OrderCheckoutIdempotencyTests {

    @Mock
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private OrderStatusHistoryRepository orderStatusHistoryRepository;

    @Mock
    private CartRepository cartRepository;

    @Mock
    private CartItemRepository cartItemRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @InjectMocks
    private OrderCheckoutTxManager txManager;

    private UUID customerId;
    private String idempotencyKey;
    private String requestHash;

    @BeforeEach
    void setUp() {
        customerId = UUID.randomUUID();
        idempotencyKey = UUID.randomUUID().toString();
        requestHash = "hash1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";
    }

    @Test
    @DisplayName("Positive: atomic insertIfNotExists=1 returns new claim successfully")
    void testClaimIdempotency_NewInsert() {
        when(idempotencyRecordRepository.insertIfNotExists(any(), eq(customerId), eq("CHECKOUT"),
                eq(idempotencyKey), eq(requestHash), any(), any(), any())).thenReturn(1);

        IdempotencyRecord savedRecord = IdempotencyRecord.builder()
                .id(UUID.randomUUID())
                .customerId(customerId)
                .operation("CHECKOUT")
                .idempotencyKey(idempotencyKey)
                .requestHash(requestHash)
                .status(IdempotencyStatus.IN_PROGRESS)
                .attemptOrderId(UUID.randomUUID())
                .build();
        when(idempotencyRecordRepository.findById(any())).thenReturn(Optional.of(savedRecord));

        ClaimResult result = txManager.claimIdempotency(customerId, idempotencyKey, requestHash, 24);

        assertTrue(result.isNewOrReclaimed());
        assertFalse(result.isCompleted());
        assertEquals(savedRecord, result.record());
    }

    @Test
    @DisplayName("Positive: duplicate insert (0) with COMPLETED status replays saved response")
    void testClaimIdempotency_DuplicateCompletedReplays() throws Exception {
        when(idempotencyRecordRepository.insertIfNotExists(any(), eq(customerId), eq("CHECKOUT"),
                eq(idempotencyKey), eq(requestHash), any(), any(), any())).thenReturn(0);

        CheckoutResponse cachedResponse = CheckoutResponse.builder()
                .orderId(UUID.randomUUID())
                .orderCode("ORD-REPLAY")
                .status(OrderStatus.PENDING)
                .totalAmount(new BigDecimal("100000"))
                .currency("VND")
                .createdAt(Instant.now())
                .build();

        IdempotencyRecord existing = IdempotencyRecord.builder()
                .id(UUID.randomUUID())
                .customerId(customerId)
                .operation("CHECKOUT")
                .idempotencyKey(idempotencyKey)
                .requestHash(requestHash)
                .status(IdempotencyStatus.COMPLETED)
                .responseStatus(201)
                .responseBody(objectMapper.writeValueAsString(cachedResponse))
                .build();

        when(idempotencyRecordRepository.findByCustomerIdAndOperationAndIdempotencyKey(customerId, "CHECKOUT", idempotencyKey))
                .thenReturn(Optional.of(existing));

        ClaimResult result = txManager.claimIdempotency(customerId, idempotencyKey, requestHash, 24);

        assertFalse(result.isNewOrReclaimed());
        assertTrue(result.isCompleted());
        assertEquals("ORD-REPLAY", result.replayedResponse().getOrderCode());
    }

    @Test
    @DisplayName("Negative: duplicate key with different requestHash throws ConflictException IDEMPOTENCY_KEY_REUSED")
    void testClaimIdempotency_HashMismatchThrowsConflict() {
        when(idempotencyRecordRepository.insertIfNotExists(any(), eq(customerId), eq("CHECKOUT"),
                eq(idempotencyKey), eq(requestHash), any(), any(), any())).thenReturn(0);

        IdempotencyRecord existing = IdempotencyRecord.builder()
                .id(UUID.randomUUID())
                .customerId(customerId)
                .operation("CHECKOUT")
                .idempotencyKey(idempotencyKey)
                .requestHash("differentHash")
                .status(IdempotencyStatus.COMPLETED)
                .build();

        when(idempotencyRecordRepository.findByCustomerIdAndOperationAndIdempotencyKey(customerId, "CHECKOUT", idempotencyKey))
                .thenReturn(Optional.of(existing));

        ConflictException ex = assertThrows(ConflictException.class, () ->
                txManager.claimIdempotency(customerId, idempotencyKey, requestHash, 24));
        assertEquals(ErrorCode.IDEMPOTENCY_KEY_REUSED, ex.getErrorCode());
    }

    @Test
    @DisplayName("Negative: duplicate insert with IN_PROGRESS status throws ConflictException STATE_CONFLICT")
    void testClaimIdempotency_InProgressThrowsConflict() {
        when(idempotencyRecordRepository.insertIfNotExists(any(), eq(customerId), eq("CHECKOUT"),
                eq(idempotencyKey), eq(requestHash), any(), any(), any())).thenReturn(0);

        IdempotencyRecord existing = IdempotencyRecord.builder()
                .id(UUID.randomUUID())
                .customerId(customerId)
                .operation("CHECKOUT")
                .idempotencyKey(idempotencyKey)
                .requestHash(requestHash)
                .status(IdempotencyStatus.IN_PROGRESS)
                .build();

        when(idempotencyRecordRepository.findByCustomerIdAndOperationAndIdempotencyKey(customerId, "CHECKOUT", idempotencyKey))
                .thenReturn(Optional.of(existing));

        ConflictException ex = assertThrows(ConflictException.class, () ->
                txManager.claimIdempotency(customerId, idempotencyKey, requestHash, 24));
        assertEquals(ErrorCode.STATE_CONFLICT, ex.getErrorCode());
    }

    @Test
    @DisplayName("Positive: failed status reclaims atomically with CAS and returns new attempt")
    void testClaimIdempotency_FailedReclaimsAtomically() {
        when(idempotencyRecordRepository.insertIfNotExists(any(), eq(customerId), eq("CHECKOUT"),
                eq(idempotencyKey), eq(requestHash), any(), any(), any())).thenReturn(0);

        UUID recordId = UUID.randomUUID();
        IdempotencyRecord existing = IdempotencyRecord.builder()
                .id(recordId)
                .customerId(customerId)
                .operation("CHECKOUT")
                .idempotencyKey(idempotencyKey)
                .requestHash(requestHash)
                .status(IdempotencyStatus.FAILED)
                .build();

        when(idempotencyRecordRepository.findByCustomerIdAndOperationAndIdempotencyKey(customerId, "CHECKOUT", idempotencyKey))
                .thenReturn(Optional.of(existing));

        when(idempotencyRecordRepository.reclaimFailedRecord(eq(recordId), any(), any(), any())).thenReturn(1);
        when(idempotencyRecordRepository.findById(recordId)).thenReturn(Optional.of(existing));

        ClaimResult result = txManager.claimIdempotency(customerId, idempotencyKey, requestHash, 24);

        assertTrue(result.isNewOrReclaimed());
        assertFalse(result.isCompleted());
    }

    @Test
    @DisplayName("Negative: commitCheckout aborts if cart quantity changed during remote calls")
    void testCommitCheckout_CartQuantityChanged_ThrowsConflict() {
        UUID cartId = UUID.randomUUID();
        Cart currentCart = Cart.builder()
                .id(cartId)
                .customerId(customerId)
                .version(1L)
                .build();

        UUID skuId = UUID.randomUUID();
        OrderItemSnapshot snapshot = new OrderItemSnapshot(
                skuId, "SKU-1", "Product", "Sku", "{}", new BigDecimal("50000"), 2, new BigDecimal("100000")
        );

        // Fresh cart in DB has quantity 3 instead of 2!
        Cart freshCart = Cart.builder()
                .id(cartId)
                .customerId(customerId)
                .version(1L)
                .items(new ArrayList<>())
                .build();
        CartItem item = CartItem.builder()
                .skuId(skuId)
                .quantity(3)
                .build();
        freshCart.getItems().add(item);

        when(cartRepository.findByIdWithItemsForUpdate(cartId)).thenReturn(Optional.of(freshCart));

        IdentityAddressResponse address = IdentityAddressResponse.builder()
                .recipientName("A").phone("0123").addressLine1("B").district("C").city("D").country("VN").build();

        assertThrows(ConflictException.class, () ->
                txManager.commitCheckout(customerId, currentCart, List.of(snapshot), address,
                        UUID.randomUUID(), idempotencyKey, UUID.randomUUID(), "corr-1"));
    }
}
