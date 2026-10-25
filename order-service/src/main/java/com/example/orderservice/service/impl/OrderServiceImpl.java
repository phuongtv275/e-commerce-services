package com.example.orderservice.service.impl;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.constant.SecurityConstants;
import com.ecommerce.common.event.OrderCancelledEvent;
import com.ecommerce.common.event.OrderStatusChangedEvent;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.exception.ConflictException;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.ecommerce.common.exception.ValidationException;
import com.ecommerce.common.outbox.OutboxEvent;
import com.ecommerce.common.outbox.OutboxEventRepository;
import com.ecommerce.common.outbox.OutboxStatus;
import com.ecommerce.common.response.PagedResponse;
import com.example.orderservice.client.CatalogClient;
import com.example.orderservice.client.IdentityClient;
import com.example.orderservice.client.InventoryClient;
import com.example.orderservice.client.dto.*;
import com.example.orderservice.dto.*;
import com.example.orderservice.entity.*;
import com.example.orderservice.repository.*;
import com.example.orderservice.service.OrderCheckoutTxManager;
import com.example.orderservice.service.OrderCheckoutTxManager.ClaimResult;
import com.example.orderservice.service.OrderCheckoutTxManager.OrderItemSnapshot;
import com.example.orderservice.service.OrderService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final CartRepository cartRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final OrderCheckoutTxManager orderCheckoutTxManager;
    private final CatalogClient catalogClient;
    private final IdentityClient identityClient;
    private final InventoryClient inventoryClient;
    private final ObjectMapper objectMapper;

    @Value("${order.idempotency.retention-hours:24}")
    private int retentionHours;

    @Override
    public CheckoutResponse checkout(UUID customerId, String idempotencyKey, CheckoutRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
            throw new ValidationException("Idempotency-Key header không hợp lệ");
        }

        if (request == null || request.getAddressId() == null) {
            throw new ValidationException("addressId là bắt buộc");
        }

        if (!"VNPAY".equalsIgnoreCase(request.getPaymentMethod())) {
            throw new ValidationException("Phương thức thanh toán chỉ hỗ trợ VNPAY");
        }

        String requestHash = computeFingerprint(customerId, request.getAddressId(), "VNPAY");

        // 1. Claim idempotency atomically
        ClaimResult claim = orderCheckoutTxManager.claimIdempotency(customerId, idempotencyKey, requestHash, retentionHours);
        if (claim.isCompleted()) {
            log.info("Replaying cached checkout response for customerId: {}, key: {}", customerId, idempotencyKey);
            return claim.replayedResponse();
        }

        IdempotencyRecord record = claim.record();
        UUID attemptOrderId = record.getAttemptOrderId();

        // 2. Read current cart
        Cart cart = cartRepository.findByCustomerIdWithItems(customerId).orElse(null);
        if (cart == null || cart.getItems().isEmpty()) {
            orderCheckoutTxManager.markIdempotencyFailed(record.getId());
            throw new ConflictException(ErrorCode.STATE_CONFLICT, "Giỏ hàng đang trống");
        }

        // 3. Identity lookup (outside DB tx)
        IdentityAddressResponse address;
        try {
            address = identityClient.getAddress(customerId, request.getAddressId());
        } catch (Exception e) {
            log.error("Failed to fetch address snapshot for customer {}: {}", customerId, e.getMessage());
            orderCheckoutTxManager.markIdempotencyFailed(record.getId());
            throw e;
        }

        // 4. Catalog batch lookup (outside DB tx)
        List<UUID> skuIds = cart.getItems().stream().map(CartItem::getSkuId).toList();
        CatalogBatchSkuResponse catalogBatch;
        try {
            catalogBatch = catalogClient.getBatchSkus(skuIds);
        } catch (Exception e) {
            log.error("Failed to query catalog for SKUs {}: {}", skuIds, e.getMessage());
            orderCheckoutTxManager.markIdempotencyFailed(record.getId());
            throw e;
        }

        Map<UUID, CatalogSkuItemResponse> catalogMap = catalogBatch.getItems().stream()
                .collect(Collectors.toMap(CatalogSkuItemResponse::getSkuId, Function.identity(), (a, b) -> a));

        List<OrderItemSnapshot> itemsSnapshot = new ArrayList<>();
        List<InventoryReservationItemRequest> reservationItems = new ArrayList<>();

        for (CartItem cartItem : cart.getItems()) {
            CatalogSkuItemResponse catalogSku = catalogMap.get(cartItem.getSkuId());
            if (catalogSku == null || !"ACTIVE".equalsIgnoreCase(catalogSku.getStatus())
                    || catalogBatch.getMissingSkuIds().contains(cartItem.getSkuId())) {
                orderCheckoutTxManager.markIdempotencyFailed(record.getId());
                throw new ConflictException(ErrorCode.STATE_CONFLICT, "Sản phẩm " + cartItem.getSkuCode() + " không tồn tại hoặc đã ngừng bán");
            }

            BigDecimal unitPrice = catalogSku.getPrice();
            // Reject fractional VND
            if (unitPrice.scale() > 0 && unitPrice.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) != 0) {
                orderCheckoutTxManager.markIdempotencyFailed(record.getId());
                throw new ValidationException("Giá sản phẩm không hỗ trợ số lẻ thập phân cho VND: " + unitPrice);
            }

            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(cartItem.getQuantity()));

            itemsSnapshot.add(new OrderItemSnapshot(
                    catalogSku.getSkuId(),
                    catalogSku.getSkuCode(),
                    catalogSku.getProductName(),
                    catalogSku.getSkuName(),
                    catalogSku.getAttributes() != null ? catalogSku.getAttributes() : "{}",
                    unitPrice,
                    cartItem.getQuantity(),
                    lineTotal
            ));

            reservationItems.add(new InventoryReservationItemRequest(cartItem.getSkuId(), cartItem.getQuantity()));
        }

        // 5. Inventory reservation (outside DB tx)
        try {
            inventoryClient.reserve(attemptOrderId, reservationItems);
        } catch (Exception e) {
            log.error("Inventory reservation failed for attemptOrderId {}: {}", attemptOrderId, e.getMessage());
            orderCheckoutTxManager.markIdempotencyFailed(record.getId());
            throw e;
        }

        // 6. Local atomic commit & synchronous compensation
        String correlationId = MDC.get(SecurityConstants.MDC_CORRELATION_ID);
        try {
            return orderCheckoutTxManager.commitCheckout(
                    customerId,
                    cart,
                    itemsSnapshot,
                    address,
                    attemptOrderId,
                    idempotencyKey,
                    record.getId(),
                    correlationId
            );
        } catch (Exception e) {
            log.error("Local checkout commit failed for attemptOrderId {}, rolling back and releasing reservation", attemptOrderId, e);
            // Synchronously release reservation
            inventoryClient.release(attemptOrderId, "Order local commit failed: " + e.getMessage());
            // Mark idempotency as FAILED
            orderCheckoutTxManager.markIdempotencyFailed(record.getId());
            throw e;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerOrderDetailResponse getOrderDetail(UUID customerId, UUID orderId) {
        Order order = orderRepository.findByIdAndCustomerIdWithDetails(orderId, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Đơn hàng không tồn tại"));

        return mapToDetailResponse(order);
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<CustomerOrderResponse> getOrders(UUID customerId, OrderStatus status, Pageable pageable) {
        Page<Order> page;
        if (status != null) {
            page = orderRepository.findByCustomerIdAndStatus(customerId, status, pageable);
        } else {
            page = orderRepository.findByCustomerId(customerId, pageable);
        }

        List<CustomerOrderResponse> items = page.getContent().stream()
                .map(o -> CustomerOrderResponse.builder()
                        .orderId(o.getId())
                        .orderCode(o.getOrderCode())
                        .status(o.getStatus())
                        .totalAmount(o.getTotalAmount())
                        .currency(o.getCurrency())
                        .totalItems(o.getItems() != null ? o.getItems().size() : 0)
                        .createdAt(o.getCreatedAt())
                        .build())
                .toList();

        return PagedResponse.<CustomerOrderResponse>builder()
                .content(items)
                .pageNumber(page.getNumber())
                .pageSize(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .isLast(page.isLast())
                .isFirst(page.isFirst())
                .build();
    }

    @Override
    @Transactional
    public CustomerOrderDetailResponse cancelOrder(UUID customerId, UUID orderId, CancelOrderRequest request) {
        Order order = orderRepository.findByIdAndCustomerId(orderId, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Đơn hàng không tồn tại"));

        if (order.getStatus() == OrderStatus.CANCELLED) {
            return getOrderDetail(customerId, orderId);
        }

        if (order.getStatus() != OrderStatus.PENDING) {
            throw new ConflictException(ErrorCode.STATE_CONFLICT, "Chỉ có thể hủy đơn hàng khi ở trạng thái PENDING");
        }

        String reason = (request != null && request.getReason() != null) ? request.getReason() : "Khách hàng hủy đơn";
        String correlationId = MDC.get(SecurityConstants.MDC_CORRELATION_ID);
        String orderCode = order.getOrderCode();
        Instant now = Instant.now();

        // Atomic CAS conditional update (PENDING -> CANCELLED) with clearAutomatically = true
        int updated = orderRepository.transitionOrderStatus(
                orderId,
                OrderStatus.PENDING,
                OrderStatus.CANCELLED,
                reason,
                now,
                now
        );

        if (updated != 1) {
            Order refreshed = orderRepository.findByIdAndCustomerId(orderId, customerId)
                    .orElseThrow(() -> new ResourceNotFoundException("Đơn hàng không tồn tại"));
            if (refreshed.getStatus() == OrderStatus.CANCELLED) {
                return mapToDetailResponse(refreshed);
            }
            throw new ConflictException(ErrorCode.STATE_CONFLICT, "Đơn hàng đã thay đổi trạng thái trước khi hủy");
        }

        // Context is cleared by @Modifying; obtain reference for history foreign key
        Order orderRef = orderRepository.getReferenceById(orderId);

        // Only the winning CAS writes history and outbox
        OrderStatusHistory history = OrderStatusHistory.builder()
                .order(orderRef)
                .oldStatus(OrderStatus.PENDING.name())
                .newStatus(OrderStatus.CANCELLED.name())
                .reason(reason)
                .actor(customerId.toString())
                .correlationId(correlationId)
                .build();
        orderStatusHistoryRepository.save(history);

        // Outbox order.status-changed
        OrderStatusChangedEvent statusEvent = OrderStatusChangedEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType("order.status-changed")
                .eventVersion(1)
                .occurredAt(now)
                .correlationId(correlationId)
                .producer("order-service")
                .orderId(orderId)
                .orderCode(orderCode)
                .oldStatus(OrderStatus.PENDING.name())
                .newStatus(OrderStatus.CANCELLED.name())
                .build();

        // Outbox order.cancelled
        OrderCancelledEvent cancelEvent = OrderCancelledEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType("order.cancelled")
                .eventVersion(1)
                .occurredAt(now)
                .correlationId(correlationId)
                .producer("order-service")
                .orderId(orderId)
                .reason(reason)
                .refundRequired(false)
                .cancelledBy(customerId.toString())
                .build();

        saveOutboxEvent(orderId.toString(), "order.status-changed", statusEvent, correlationId);
        saveOutboxEvent(orderId.toString(), "order.cancelled", cancelEvent, correlationId);

        return getOrderDetail(customerId, orderId);
    }

    @Override
    @Transactional(readOnly = true)
    public InternalOrderPaymentResponse getInternalOrderForPayment(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Đơn hàng không tồn tại"));

        return InternalOrderPaymentResponse.builder()
                .orderId(order.getId())
                .customerId(order.getCustomerId())
                .totalAmount(order.getTotalAmount())
                .currency(order.getCurrency())
                .status(order.getStatus().name())
                .orderCode(order.getOrderCode())
                .build();
    }

    @Override
    @Transactional
    public void handlePaymentSucceeded(UUID orderId, UUID paymentId) {
        transitionOrderStatus(orderId, OrderStatus.PENDING, OrderStatus.CONFIRMED,
                "Payment succeeded with paymentId: " + paymentId, "payment-service", false);
    }

    @Override
    @Transactional
    public void handlePaymentFailed(UUID orderId, String reason) {
        transitionOrderStatus(orderId, OrderStatus.PENDING, OrderStatus.CANCELLED,
                "Payment failed: " + reason, "payment-service", false);
    }

    @Override
    @Transactional
    public void handleReservationExpired(UUID orderId) {
        transitionOrderStatus(orderId, OrderStatus.PENDING, OrderStatus.CANCELLED,
                "Inventory reservation expired", "inventory-service", false);
    }

    @Override
    @Transactional
    public void handleReservationCommitFailed(UUID orderId, String reason) {
        transitionOrderStatus(orderId, OrderStatus.PENDING, OrderStatus.CANCELLED,
                "Inventory reservation commit failed: " + reason, "inventory-service", false);
    }

    private void transitionOrderStatus(UUID orderId, OrderStatus expectedOldStatus, OrderStatus newStatus,
                                       String reason, String actor, boolean refundRequired) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("Order {} not found for status transition to {}", orderId, newStatus);
            return;
        }

        if (order.getStatus() == newStatus) {
            log.info("Order {} already in target status {}, duplicate delivery no-op", orderId, newStatus);
            return;
        }

        // Terminal states cannot regress
        if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.COMPLETED) {
            log.warn("Order {} is in terminal status {}, cannot transition to {}", orderId, order.getStatus(), newStatus);
            return;
        }

        if (expectedOldStatus != null && order.getStatus() != expectedOldStatus) {
            log.warn("Order {} status mismatch: expected {} but was {}, skipping transition",
                    orderId, expectedOldStatus, order.getStatus());
            return;
        }

        Instant now = Instant.now();
        Instant cancelledAt = (newStatus == OrderStatus.CANCELLED) ? now : null;
        String orderCode = order.getOrderCode();

        // Atomic CAS conditional update (UPDATE ... WHERE id = :id AND status = :expected)
        int updated = orderRepository.transitionOrderStatus(
                orderId,
                expectedOldStatus != null ? expectedOldStatus : order.getStatus(),
                newStatus,
                (newStatus == OrderStatus.CANCELLED) ? reason : null,
                cancelledAt,
                now
        );

        if (updated != 1) {
            log.warn("Order {} CAS transition failed (0 rows affected). Concurrently modified by another process.", orderId);
            return;
        }

        String oldStatusStr = (expectedOldStatus != null) ? expectedOldStatus.name() : order.getStatus().name();
        String correlationId = MDC.get(SecurityConstants.MDC_CORRELATION_ID);
        Order orderRef = orderRepository.getReferenceById(orderId);

        // Record history
        OrderStatusHistory history = OrderStatusHistory.builder()
                .order(orderRef)
                .oldStatus(oldStatusStr)
                .newStatus(newStatus.name())
                .reason(reason)
                .actor(actor)
                .correlationId(correlationId)
                .build();
        orderStatusHistoryRepository.save(history);

        // Publish status-changed
        OrderStatusChangedEvent statusEvent = OrderStatusChangedEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType("order.status-changed")
                .eventVersion(1)
                .occurredAt(now)
                .correlationId(correlationId)
                .producer("order-service")
                .orderId(orderId)
                .orderCode(orderCode)
                .oldStatus(oldStatusStr)
                .newStatus(newStatus.name())
                .build();
        saveOutboxEvent(orderId.toString(), "order.status-changed", statusEvent, correlationId);

        // If cancelled, also publish order.cancelled
        if (newStatus == OrderStatus.CANCELLED) {
            OrderCancelledEvent cancelEvent = OrderCancelledEvent.builder()
                    .eventId(UUID.randomUUID())
                    .eventType("order.cancelled")
                    .eventVersion(1)
                    .occurredAt(now)
                    .correlationId(correlationId)
                    .producer("order-service")
                    .orderId(orderId)
                    .reason(reason)
                    .refundRequired(refundRequired)
                    .cancelledBy(actor)
                    .build();
            saveOutboxEvent(orderId.toString(), "order.cancelled", cancelEvent, correlationId);
        }
    }

    private void saveOutboxEvent(String aggregateId, String eventType, Object eventPayload, String correlationId) {
        try {
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateType("Order")
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .eventVersion(1)
                    .payload(objectMapper.writeValueAsString(eventPayload))
                    .status(OutboxStatus.PENDING)
                    .correlationId(correlationId)
                    .build();
            outboxEventRepository.save(outboxEvent);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize outbox event {}", eventType, e);
            throw new RuntimeException("Lỗi lưu sự kiện Outbox: " + eventType, e);
        }
    }

    private CustomerOrderDetailResponse mapToDetailResponse(Order order) {
        List<OrderItemDetailResponse> itemDetails = order.getItems().stream()
                .map(i -> OrderItemDetailResponse.builder()
                        .itemId(i.getId())
                        .skuId(i.getSkuId())
                        .skuCode(i.getSkuCode())
                        .productName(i.getProductName())
                        .skuName(i.getSkuName())
                        .attributes(i.getAttributes())
                        .unitPrice(i.getUnitPrice())
                        .quantity(i.getQuantity())
                        .lineTotal(i.getLineTotal())
                        .build())
                .toList();

        List<OrderStatusHistoryDetailResponse> historyDetails = order.getStatusHistories().stream()
                .sorted(Comparator.comparing(OrderStatusHistory::getOccurredAt))
                .map(h -> OrderStatusHistoryDetailResponse.builder()
                        .historyId(h.getId())
                        .oldStatus(h.getOldStatus())
                        .newStatus(h.getNewStatus())
                        .reason(h.getReason())
                        .actor(h.getActor())
                        .occurredAt(h.getOccurredAt())
                        .build())
                .toList();

        return CustomerOrderDetailResponse.builder()
                .orderId(order.getId())
                .orderCode(order.getOrderCode())
                .status(order.getStatus())
                .subtotal(order.getSubtotal())
                .discount(order.getDiscount())
                .tax(order.getTax())
                .shippingFee(order.getShippingFee())
                .totalAmount(order.getTotalAmount())
                .currency(order.getCurrency())
                .shippingAddress(order.getShippingAddress())
                .contactInfo(order.getContactInfo())
                .paymentMethod(order.getPaymentMethod())
                .cancelReason(order.getCancelReason())
                .cancelledAt(order.getCancelledAt())
                .createdAt(order.getCreatedAt())
                .items(itemDetails)
                .statusHistories(historyDetails)
                .build();
    }

    private String computeFingerprint(UUID customerId, UUID addressId, String paymentMethod) {
        try {
            Map<String, Object> map = new TreeMap<>();
            map.put("addressId", addressId.toString());
            map.put("customerId", customerId.toString());
            map.put("paymentMethod", paymentMethod.toUpperCase());
            map.put("version", 1);
            String json = objectMapper.writeValueAsString(map);

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new RuntimeException("Lỗi tính toán fingerprint", e);
        }
    }
}
