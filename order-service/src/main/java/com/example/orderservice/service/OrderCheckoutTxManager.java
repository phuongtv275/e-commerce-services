package com.example.orderservice.service;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.event.OrderCreatedEvent;
import com.ecommerce.common.event.OrderItemPayload;
import com.ecommerce.common.exception.ConflictException;
import com.ecommerce.common.outbox.OutboxEvent;
import com.ecommerce.common.outbox.OutboxEventRepository;
import com.ecommerce.common.outbox.OutboxStatus;
import com.example.orderservice.client.dto.IdentityAddressResponse;
import com.example.orderservice.dto.CheckoutResponse;
import com.example.orderservice.entity.*;
import com.example.orderservice.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCheckoutTxManager {

    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public record ClaimResult(
            boolean isNewOrReclaimed,
            boolean isCompleted,
            IdempotencyRecord record,
            CheckoutResponse replayedResponse
    ) {}

    public record OrderItemSnapshot(
            UUID skuId,
            String skuCode,
            String productName,
            String skuName,
            String attributes,
            BigDecimal unitPrice,
            int quantity,
            BigDecimal lineTotal
    ) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ClaimResult claimIdempotency(UUID customerId, String idempotencyKey, String requestHash, int retentionHours) {
        UUID newRecordId = UUID.randomUUID();
        UUID newAttemptOrderId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(retentionHours, ChronoUnit.HOURS);

        // 1. Atomic PostgreSQL INSERT ... ON CONFLICT DO NOTHING
        int inserted = idempotencyRecordRepository.insertIfNotExists(
                newRecordId,
                customerId,
                "CHECKOUT",
                idempotencyKey,
                requestHash,
                newAttemptOrderId,
                expiresAt,
                now
        );

        if (inserted == 1) {
            IdempotencyRecord newRecord = idempotencyRecordRepository.findById(newRecordId)
                    .orElseThrow(() -> new IllegalStateException("Failed to load newly inserted idempotency record"));
            return new ClaimResult(true, false, newRecord, null);
        }

        // 2. Row exists: fetch and evaluate
        IdempotencyRecord record = idempotencyRecordRepository
                .findByCustomerIdAndOperationAndIdempotencyKey(customerId, "CHECKOUT", idempotencyKey)
                .orElseThrow(() -> new ConflictException(ErrorCode.STATE_CONFLICT, "Idempotency claim conflict"));

        if (!record.getRequestHash().equals(requestHash)) {
            throw new ConflictException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "Khóa idempotency đã được sử dụng với nội dung khác");
        }

        if (record.getStatus() == IdempotencyStatus.COMPLETED) {
            try {
                CheckoutResponse response = objectMapper.readValue(record.getResponseBody(), CheckoutResponse.class);
                return new ClaimResult(false, true, record, response);
            } catch (Exception e) {
                log.error("Failed to parse cached checkout response", e);
            }
        }

        if (record.getStatus() == IdempotencyStatus.IN_PROGRESS) {
            throw new ConflictException(ErrorCode.STATE_CONFLICT, "Yêu cầu checkout đang được xử lý, vui lòng thử lại");
        }

        // 3. Status is FAILED: Atomic CAS update to claim again
        UUID reAttemptOrderId = UUID.randomUUID();
        Instant reExpiresAt = Instant.now().plus(retentionHours, ChronoUnit.HOURS);
        int updated = idempotencyRecordRepository.reclaimFailedRecord(record.getId(), reAttemptOrderId, reExpiresAt, Instant.now());

        if (updated == 1) {
            record = idempotencyRecordRepository.findById(record.getId())
                    .orElseThrow(() -> new IllegalStateException("Failed to load reclaimed idempotency record"));
            return new ClaimResult(true, false, record, null);
        }

        // Concurrently reclaimed by another request
        throw new ConflictException(ErrorCode.STATE_CONFLICT, "Yêu cầu checkout đang được xử lý đồng thời, vui lòng thử lại");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markIdempotencyFailed(UUID recordId) {
        idempotencyRecordRepository.findById(recordId).ifPresent(record -> {
            record.setStatus(IdempotencyStatus.FAILED);
            record.setUpdatedAt(Instant.now());
            idempotencyRecordRepository.save(record);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public CheckoutResponse commitCheckout(
            UUID customerId,
            Cart currentCart,
            List<OrderItemSnapshot> itemsSnapshot,
            IdentityAddressResponse address,
            UUID attemptOrderId,
            String idempotencyKey,
            UUID idempotencyRecordId,
            String correlationId) {

        // 1. Pessimistic Write Lock on Cart to guard against concurrent mutations during commit
        Cart freshCart = cartRepository.findByIdWithItemsForUpdate(currentCart.getId())
                .orElseThrow(() -> new ConflictException(ErrorCode.STATE_CONFLICT, "Giỏ hàng không còn tồn tại"));

        // 2. Strict Invariant Verification: Version + Complete SKU & Quantity verification
        if (!freshCart.getVersion().equals(currentCart.getVersion())) {
            throw new ConflictException(ErrorCode.STATE_CONFLICT, "Phiên bản giỏ hàng đã thay đổi trong quá trình thanh toán");
        }

        if (freshCart.getItems().size() != itemsSnapshot.size()) {
            throw new ConflictException(ErrorCode.STATE_CONFLICT, "Số lượng mặt hàng trong giỏ đã thay đổi trong quá trình thanh toán");
        }

        Map<UUID, Integer> snapshotQuantities = new HashMap<>();
        for (OrderItemSnapshot snap : itemsSnapshot) {
            snapshotQuantities.put(snap.skuId(), snap.quantity());
        }

        for (CartItem cartItem : freshCart.getItems()) {
            Integer expectedQty = snapshotQuantities.get(cartItem.getSkuId());
            if (expectedQty == null || !expectedQty.equals(cartItem.getQuantity())) {
                throw new ConflictException(ErrorCode.STATE_CONFLICT,
                        "Mặt hàng hoặc số lượng trong giỏ hàng đã thay đổi trong quá trình thanh toán");
            }
        }

        // 3. Create order
        String orderCode = "ORD-" + System.currentTimeMillis() + "-" + attemptOrderId.toString().substring(0, 6).toUpperCase();
        BigDecimal subtotal = itemsSnapshot.stream()
                .map(OrderItemSnapshot::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String shippingAddressJson;
        String contactInfoJson;
        try {
            Map<String, Object> addrMap = new LinkedHashMap<>();
            addrMap.put("addressLine1", address.getAddressLine1());
            addrMap.put("addressLine2", address.getAddressLine2());
            addrMap.put("district", address.getDistrict());
            addrMap.put("city", address.getCity());
            addrMap.put("country", address.getCountry());
            shippingAddressJson = objectMapper.writeValueAsString(addrMap);

            Map<String, Object> contactMap = new LinkedHashMap<>();
            contactMap.put("recipientName", address.getRecipientName());
            contactMap.put("phone", address.getPhone());
            contactInfoJson = objectMapper.writeValueAsString(contactMap);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Lỗi serialize địa chỉ giao hàng", e);
        }

        Order order = Order.builder()
                .id(attemptOrderId)
                .orderCode(orderCode)
                .customerId(customerId)
                .status(OrderStatus.PENDING)
                .subtotal(subtotal)
                .discount(BigDecimal.ZERO)
                .tax(BigDecimal.ZERO)
                .shippingFee(BigDecimal.ZERO)
                .totalAmount(subtotal)
                .currency("VND")
                .idempotencyKey(idempotencyKey)
                .shippingAddress(shippingAddressJson)
                .contactInfo(contactInfoJson)
                .paymentMethod("VNPAY")
                .build();

        order = orderRepository.save(order);

        // 4. Create items
        List<OrderItemPayload> eventItemPayloads = new ArrayList<>();
        for (OrderItemSnapshot itemSnap : itemsSnapshot) {
            OrderItem orderItem = OrderItem.builder()
                    .order(order)
                    .skuId(itemSnap.skuId())
                    .skuCode(itemSnap.skuCode())
                    .productName(itemSnap.productName())
                    .skuName(itemSnap.skuName())
                    .attributes(itemSnap.attributes())
                    .unitPrice(itemSnap.unitPrice())
                    .quantity(itemSnap.quantity())
                    .lineTotal(itemSnap.lineTotal())
                    .build();
            orderItemRepository.save(orderItem);

            eventItemPayloads.add(OrderItemPayload.builder()
                    .skuId(itemSnap.skuId())
                    .skuCode(itemSnap.skuCode())
                    .quantity(itemSnap.quantity())
                    .unitPrice(itemSnap.unitPrice())
                    .build());
        }

        // 5. Initial history
        OrderStatusHistory history = OrderStatusHistory.builder()
                .order(order)
                .oldStatus(null)
                .newStatus(OrderStatus.PENDING.name())
                .reason("Checkout created")
                .actor(customerId.toString())
                .correlationId(correlationId)
                .build();
        orderStatusHistoryRepository.save(history);

        // 6. Outbox event (order.created)
        OrderCreatedEvent createdEvent = OrderCreatedEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType("order.created")
                .eventVersion(1)
                .occurredAt(Instant.now())
                .correlationId(correlationId)
                .producer("order-service")
                .orderId(order.getId())
                .customerId(customerId)
                .orderCode(orderCode)
                .items(eventItemPayloads)
                .totalAmount(subtotal)
                .currency("VND")
                .reservationExpiresAt(Instant.now().plus(15, ChronoUnit.MINUTES))
                .build();

        try {
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateType("Order")
                    .aggregateId(order.getId().toString())
                    .eventType("order.created")
                    .eventVersion(1)
                    .payload(objectMapper.writeValueAsString(createdEvent))
                    .status(OutboxStatus.PENDING)
                    .correlationId(correlationId)
                    .build();
            outboxEventRepository.save(outboxEvent);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Lỗi serialize OrderCreatedEvent", e);
        }

        // 7. Response construction
        CheckoutResponse checkoutResponse = CheckoutResponse.builder()
                .orderId(order.getId())
                .orderCode(order.getOrderCode())
                .status(order.getStatus())
                .totalAmount(order.getTotalAmount())
                .currency(order.getCurrency())
                .createdAt(order.getCreatedAt())
                .build();

        // 8. Update Idempotency Record to COMPLETED
        idempotencyRecordRepository.findById(idempotencyRecordId).ifPresent(rec -> {
            rec.setStatus(IdempotencyStatus.COMPLETED);
            rec.setResponseStatus(201);
            rec.setUpdatedAt(Instant.now());
            try {
                rec.setResponseBody(objectMapper.writeValueAsString(checkoutResponse));
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize checkout response for idempotency", e);
            }
            idempotencyRecordRepository.save(rec);
        });

        // 9. Clear cart items and update cart version
        freshCart.getItems().clear();
        freshCart.setUpdatedAt(Instant.now());
        cartItemRepository.deleteAllByCartId(freshCart.getId());
        cartRepository.save(freshCart);

        return checkoutResponse;
    }
}
