package com.example.orderservice.service;

import com.ecommerce.common.exception.ResourceNotFoundException;
import com.example.orderservice.dto.InternalOrderNotificationResponse;
import com.example.orderservice.entity.Order;
import com.example.orderservice.entity.OrderItem;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.repository.OrderRepository;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderNotificationSnapshotTests {

    @Mock
    private OrderRepository orderRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private OrderServiceImpl orderService;

    private UUID orderId;
    private UUID customerId;
    private Order testOrder;

    @BeforeEach
    void setUp() {
        orderId = UUID.randomUUID();
        customerId = UUID.randomUUID();

        OrderItem item1 = OrderItem.builder()
                .id(UUID.randomUUID())
                .skuId(UUID.randomUUID())
                .skuCode("SKU-100")
                .productName("Áo thun nam")
                .skuName("Trắng / L")
                .quantity(2)
                .unitPrice(new BigDecimal("150000"))
                .lineTotal(new BigDecimal("300000"))
                .build();

        OrderItem item2 = OrderItem.builder()
                .id(UUID.randomUUID())
                .skuId(UUID.randomUUID())
                .skuCode("SKU-200")
                .productName("Quần jeans")
                .skuName("Xanh / 32")
                .quantity(1)
                .unitPrice(new BigDecimal("450000"))
                .lineTotal(new BigDecimal("450000"))
                .build();

        String contactInfoJson = "{\"recipientName\":\"Trần Văn B\",\"phone\":\"0909123456\"}";
        String shippingAddressJson = "{\"addressLine1\":\"456 Nguyễn Huệ\",\"addressLine2\":\"Tầng 5\",\"district\":\"Quận 1\",\"city\":\"Hồ Chí Minh\",\"country\":\"Việt Nam\"}";

        testOrder = Order.builder()
                .id(orderId)
                .orderCode("ORD-2026-TEST")
                .customerId(customerId)
                .status(OrderStatus.CONFIRMED)
                .subtotal(new BigDecimal("750000"))
                .discount(new BigDecimal("50000"))
                .tax(BigDecimal.ZERO)
                .shippingFee(new BigDecimal("30000"))
                .totalAmount(new BigDecimal("730000"))
                .currency("VND")
                .paymentMethod("VNPAY")
                .contactInfo(contactInfoJson)
                .shippingAddress(shippingAddressJson)
                .createdAt(Instant.now())
                .items(List.of(item1, item2))
                .build();
    }

    @Test
    @DisplayName("POS-SVC-01: Correctly maps order, items, and shipping info for notification snapshot")
    void testGetInternalOrderForNotification_Success() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));

        InternalOrderNotificationResponse response = orderService.getInternalOrderForNotification(orderId);

        assertThat(response).isNotNull();
        assertThat(response.getOrderId()).isEqualTo(orderId);
        assertThat(response.getCustomerId()).isEqualTo(customerId);
        assertThat(response.getOrderCode()).isEqualTo("ORD-2026-TEST");
        assertThat(response.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(response.getTotalAmount()).isEqualByComparingTo(new BigDecimal("730000"));
        assertThat(response.getCurrency()).isEqualTo("VND");
        assertThat(response.getCreatedAt()).isNotNull();

        // Shipping address snapshot verification (trimmed: recipient, address components, formatted address, no phone)
        assertThat(response.getShippingAddress()).isNotNull();
        assertThat(response.getShippingAddress().getRecipientName()).isEqualTo("Trần Văn B");
        assertThat(response.getShippingAddress().getAddressLine1()).isEqualTo("456 Nguyễn Huệ");
        assertThat(response.getShippingAddress().getAddressLine2()).isEqualTo("Tầng 5");
        assertThat(response.getShippingAddress().getDistrict()).isEqualTo("Quận 1");
        assertThat(response.getShippingAddress().getCity()).isEqualTo("Hồ Chí Minh");
        assertThat(response.getShippingAddress().getCountry()).isEqualTo("Việt Nam");
        assertThat(response.getShippingAddress().getFormattedAddress())
                .isEqualTo("456 Nguyễn Huệ, Tầng 5, Quận 1, Hồ Chí Minh, Việt Nam");

        // Items verification (trimmed: productName, skuName, quantity, unitPrice, lineTotal)
        assertThat(response.getItems()).hasSize(2);
        assertThat(response.getItems().get(0).getProductName()).isEqualTo("Áo thun nam");
        assertThat(response.getItems().get(0).getSkuName()).isEqualTo("Trắng / L");
        assertThat(response.getItems().get(0).getQuantity()).isEqualTo(2);
        assertThat(response.getItems().get(0).getUnitPrice()).isEqualByComparingTo(new BigDecimal("150000"));
        assertThat(response.getItems().get(0).getLineTotal()).isEqualByComparingTo(new BigDecimal("300000"));

        assertThat(response.getItems().get(1).getProductName()).isEqualTo("Quần jeans");
        assertThat(response.getItems().get(1).getSkuName()).isEqualTo("Xanh / 32");
        assertThat(response.getItems().get(1).getQuantity()).isEqualTo(1);
        assertThat(response.getItems().get(1).getUnitPrice()).isEqualByComparingTo(new BigDecimal("450000"));
        assertThat(response.getItems().get(1).getLineTotal()).isEqualByComparingTo(new BigDecimal("450000"));
    }

    @Test
    @DisplayName("NEG-SVC-01: Non-existent order throws ResourceNotFoundException")
    void testGetInternalOrderForNotification_NotFound() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getInternalOrderForNotification(orderId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Đơn hàng không tồn tại");
    }

    @Test
    @DisplayName("POS-SVC-02: Handles null or malformed JSON in contact/shipping gracefully")
    void testGetInternalOrderForNotification_MalformedJson_HandlesGracefully() {
        testOrder.setContactInfo("invalid-json");
        testOrder.setShippingAddress(null);
        testOrder.setItems(null);

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));

        InternalOrderNotificationResponse response = orderService.getInternalOrderForNotification(orderId);

        assertThat(response).isNotNull();
        assertThat(response.getItems()).isEmpty();
        assertThat(response.getShippingAddress()).isNotNull();
        assertThat(response.getShippingAddress().getRecipientName()).isNull();
        assertThat(response.getShippingAddress().getFormattedAddress()).isEmpty();
    }
}
