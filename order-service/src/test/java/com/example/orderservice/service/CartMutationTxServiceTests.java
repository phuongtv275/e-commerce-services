package com.example.orderservice.service;

import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.example.orderservice.client.dto.CatalogSkuItemResponse;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.CartItem;
import com.example.orderservice.repository.CartItemRepository;
import com.example.orderservice.repository.CartRepository;
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
class CartMutationTxServiceTests {

    @Mock
    private CartRepository cartRepository;

    @Mock
    private CartItemRepository cartItemRepository;

    @InjectMocks
    private CartMutationTxService cartMutationTxService;

    private UUID customerId;
    private UUID skuId;
    private Cart cart;

    @BeforeEach
    void setUp() {
        customerId = UUID.randomUUID();
        skuId = UUID.randomUUID();
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
    @DisplayName("Positive: mutateAddToCart creates cart item, bumps updatedAt scalar and saves")
    void testMutateAddToCart_Success() {
        CatalogSkuItemResponse sku = CatalogSkuItemResponse.builder()
                .skuId(skuId)
                .skuCode("SKU-100")
                .price(new BigDecimal("50000"))
                .status("ACTIVE")
                .build();

        when(cartRepository.findByCustomerIdWithItems(customerId)).thenReturn(Optional.of(cart));
        when(cartRepository.save(any(Cart.class))).thenAnswer(i -> i.getArgument(0));

        cartMutationTxService.mutateAddToCart(customerId, sku, 2);

        assertEquals(1, cart.getItems().size());
        assertEquals(2, cart.getItems().getFirst().getQuantity());
        assertNotNull(cart.getUpdatedAt());
        verify(cartRepository).save(cart);
    }

    @Test
    @DisplayName("Positive: mutateUpdateCartItem with 0 quantity deletes item and saves cart")
    void testMutateUpdateCartItem_ZeroQuantityDeletes() {
        UUID itemId = UUID.randomUUID();
        CartItem item = CartItem.builder()
                .id(itemId)
                .cart(cart)
                .skuId(skuId)
                .quantity(3)
                .unitPrice(new BigDecimal("50000"))
                .build();
        cart.getItems().add(item);

        when(cartItemRepository.findByIdAndCartCustomerId(itemId, customerId)).thenReturn(Optional.of(item));
        when(cartRepository.save(any(Cart.class))).thenAnswer(i -> i.getArgument(0));

        cartMutationTxService.mutateUpdateCartItem(customerId, itemId, 0);

        assertTrue(cart.getItems().isEmpty());
        verify(cartItemRepository).delete(item);
        verify(cartRepository).save(cart);
    }

    @Test
    @DisplayName("Negative: mutateUpdateCartItem for foreign item throws ResourceNotFoundException")
    void testMutateUpdateCartItem_ForeignItem() {
        UUID itemId = UUID.randomUUID();
        when(cartItemRepository.findByIdAndCartCustomerId(itemId, customerId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                cartMutationTxService.mutateUpdateCartItem(customerId, itemId, 2));
    }
}
