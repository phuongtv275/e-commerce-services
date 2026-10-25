package com.example.orderservice.service;

import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.example.orderservice.client.CatalogClient;
import com.example.orderservice.client.InventoryClient;
import com.example.orderservice.client.dto.*;
import com.example.orderservice.dto.*;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.CartItem;
import com.example.orderservice.repository.CartItemRepository;
import com.example.orderservice.repository.CartRepository;
import com.example.orderservice.service.CartMutationTxService.CartData;
import com.example.orderservice.service.CartMutationTxService.CartItemSnapshot;
import com.example.orderservice.service.impl.CartServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CartServiceTests {

    @Mock
    private CartMutationTxService cartMutationTxService;

    @Mock
    private CatalogClient catalogClient;

    @Mock
    private InventoryClient inventoryClient;

    @InjectMocks
    private CartServiceImpl cartService;

    private UUID customerId;
    private UUID skuId;
    private UUID cartId;

    @BeforeEach
    void setUp() {
        customerId = UUID.randomUUID();
        skuId = UUID.randomUUID();
        cartId = UUID.randomUUID();
    }

    @Test
    @DisplayName("Positive: getCart returns items, subtotal and empty warnings when catalog and stock match")
    void testGetCart_Success() {
        CartItemSnapshot snapshot = new CartItemSnapshot(
                UUID.randomUUID(), skuId, "SKU-100", 2, new BigDecimal("50000"), Instant.now()
        );
        CartData cartData = new CartData(cartId, 1L, List.of(snapshot));
        when(cartMutationTxService.loadCartData(customerId)).thenReturn(cartData);

        CatalogSkuItemResponse catalogItem = CatalogSkuItemResponse.builder()
                .skuId(skuId)
                .skuCode("SKU-100")
                .productName("Áo Polo")
                .skuName("Xanh L")
                .price(new BigDecimal("50000"))
                .status("ACTIVE")
                .build();
        when(catalogClient.getBatchSkus(List.of(skuId)))
                .thenReturn(new CatalogBatchSkuResponse(List.of(catalogItem), Collections.emptyList()));

        InventoryStockItemResponse stockItem = new InventoryStockItemResponse(skuId, 10);
        when(inventoryClient.getBatchStocks(List.of(skuId)))
                .thenReturn(new InventoryStockBatchResponse(List.of(stockItem), Collections.emptyList()));

        CartResponse response = cartService.getCart(customerId);

        assertNotNull(response);
        assertEquals(cartId, response.getCartId());
        assertEquals(1, response.getItems().size());
        assertEquals(new BigDecimal("100000"), response.getSubtotal());
        assertTrue(response.getWarnings().isEmpty());
    }

    @Test
    @DisplayName("Positive: getCart detects price change and insufficient stock warnings")
    void testGetCart_WithWarnings() {
        CartItemSnapshot snapshot = new CartItemSnapshot(
                UUID.randomUUID(), skuId, "SKU-100", 5, new BigDecimal("40000"), Instant.now()
        );
        CartData cartData = new CartData(cartId, 1L, List.of(snapshot));
        when(cartMutationTxService.loadCartData(customerId)).thenReturn(cartData);

        CatalogSkuItemResponse catalogItem = CatalogSkuItemResponse.builder()
                .skuId(skuId)
                .skuCode("SKU-100")
                .productName("Áo Polo")
                .price(new BigDecimal("50000")) // Price changed
                .status("ACTIVE")
                .build();
        when(catalogClient.getBatchSkus(List.of(skuId)))
                .thenReturn(new CatalogBatchSkuResponse(List.of(catalogItem), Collections.emptyList()));

        InventoryStockItemResponse stockItem = new InventoryStockItemResponse(skuId, 2); // Stock insufficient (2 < 5)
        when(inventoryClient.getBatchStocks(List.of(skuId)))
                .thenReturn(new InventoryStockBatchResponse(List.of(stockItem), Collections.emptyList()));

        CartResponse response = cartService.getCart(customerId);

        assertNotNull(response);
        assertEquals(2, response.getWarnings().size());
        assertTrue(response.getWarnings().stream().anyMatch(w -> "PRICE_CHANGED".equals(w.getType())));
        assertTrue(response.getWarnings().stream().anyMatch(w -> "INSUFFICIENT_STOCK".equals(w.getType())));
    }

    @Test
    @DisplayName("Positive: addToCart validates catalog outside DB tx, then calls transactional mutation")
    void testAddToCart_Success() {
        AddToCartRequest req = new AddToCartRequest(skuId, 2);

        CatalogSkuItemResponse catalogItem = CatalogSkuItemResponse.builder()
                .skuId(skuId)
                .skuCode("SKU-100")
                .productName("Áo Polo")
                .price(new BigDecimal("50000"))
                .status("ACTIVE")
                .build();
        when(catalogClient.getBatchSkus(List.of(skuId)))
                .thenReturn(new CatalogBatchSkuResponse(List.of(catalogItem), Collections.emptyList()));

        CartItemSnapshot snapshot = new CartItemSnapshot(
                UUID.randomUUID(), skuId, "SKU-100", 2, new BigDecimal("50000"), Instant.now()
        );
        when(cartMutationTxService.loadCartData(customerId))
                .thenReturn(new CartData(cartId, 1L, List.of(snapshot)));

        InventoryStockItemResponse stockItem = new InventoryStockItemResponse(skuId, 10);
        when(inventoryClient.getBatchStocks(anyList()))
                .thenReturn(new InventoryStockBatchResponse(List.of(stockItem), Collections.emptyList()));

        CartResponse response = cartService.addToCart(customerId, req);

        assertNotNull(response);
        verify(cartMutationTxService).mutateAddToCart(customerId, catalogItem, 2);
    }

    @Test
    @DisplayName("Negative: addToCart with negative or zero quantity throws BusinessException")
    void testAddToCart_InvalidQuantity() {
        AddToCartRequest req = new AddToCartRequest(skuId, 0);
        assertThrows(BusinessException.class, () -> cartService.addToCart(customerId, req));
    }

    @Test
    @DisplayName("Negative: addToCart with inactive SKU throws BusinessException")
    void testAddToCart_InactiveSku() {
        AddToCartRequest req = new AddToCartRequest(skuId, 1);

        CatalogSkuItemResponse catalogItem = CatalogSkuItemResponse.builder()
                .skuId(skuId)
                .skuCode("SKU-100")
                .price(new BigDecimal("50000"))
                .status("INACTIVE")
                .build();
        when(catalogClient.getBatchSkus(List.of(skuId)))
                .thenReturn(new CatalogBatchSkuResponse(List.of(catalogItem), Collections.emptyList()));

        assertThrows(BusinessException.class, () -> cartService.addToCart(customerId, req));
        verifyNoInteractions(cartMutationTxService);
    }
}
