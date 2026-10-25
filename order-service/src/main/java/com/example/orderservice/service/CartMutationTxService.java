package com.example.orderservice.service;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.example.orderservice.client.dto.CatalogSkuItemResponse;
import com.example.orderservice.entity.Cart;
import com.example.orderservice.entity.CartItem;
import com.example.orderservice.repository.CartItemRepository;
import com.example.orderservice.repository.CartRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Component
@RequiredArgsConstructor
public class CartMutationTxService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;

    private static final int MAX_CART_ITEMS = 100;

    public record CartItemSnapshot(
            UUID itemId,
            UUID skuId,
            String skuCode,
            int quantity,
            BigDecimal unitPrice,
            Instant addedAt
    ) {}

    public record CartData(
            UUID cartId,
            Long version,
            List<CartItemSnapshot> items
    ) {}

    @Transactional
    public CartData loadCartData(UUID customerId) {
        Cart cart = cartRepository.findByCustomerIdWithItems(customerId)
                .orElseGet(() -> {
                    Cart newCart = Cart.builder()
                            .customerId(customerId)
                            .items(new ArrayList<>())
                            .build();
                    return cartRepository.save(newCart);
                });

        List<CartItemSnapshot> snapshots = cart.getItems().stream()
                .map(i -> new CartItemSnapshot(i.getId(), i.getSkuId(), i.getSkuCode(), i.getQuantity(), i.getUnitPrice(), i.getAddedAt()))
                .toList();

        return new CartData(cart.getId(), cart.getVersion(), snapshots);
    }

    @Transactional
    public void mutateAddToCart(UUID customerId, CatalogSkuItemResponse sku, int quantity) {
        Cart cart = cartRepository.findByCustomerIdWithItems(customerId)
                .orElseGet(() -> {
                    Cart newCart = Cart.builder()
                            .customerId(customerId)
                            .items(new ArrayList<>())
                            .build();
                    return cartRepository.save(newCart);
                });

        Optional<CartItem> existingItemOpt = cart.getItems().stream()
                .filter(item -> item.getSkuId().equals(sku.getSkuId()))
                .findFirst();

        if (existingItemOpt.isPresent()) {
            CartItem existingItem = existingItemOpt.get();
            existingItem.setQuantity(existingItem.getQuantity() + quantity);
            existingItem.setUnitPrice(sku.getPrice());
            existingItem.setSkuCode(sku.getSkuCode());
        } else {
            if (cart.getItems().size() >= MAX_CART_ITEMS) {
                throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "Giỏ hàng vượt quá số lượng tối đa 100 sản phẩm");
            }
            CartItem newItem = CartItem.builder()
                    .cart(cart)
                    .skuId(sku.getSkuId())
                    .skuCode(sku.getSkuCode())
                    .quantity(quantity)
                    .unitPrice(sku.getPrice())
                    .build();
            cart.getItems().add(newItem);
        }

        // Explicit scalar update ensures Cart.version bump
        cart.setUpdatedAt(Instant.now());
        cartRepository.save(cart);
    }

    @Transactional
    public void mutateUpdateCartItem(UUID customerId, UUID itemId, int quantity) {
        CartItem cartItem = cartItemRepository.findByIdAndCartCustomerId(itemId, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Mục giỏ hàng không tồn tại"));

        Cart cart = cartItem.getCart();

        if (quantity == 0) {
            cart.getItems().remove(cartItem);
            cartItemRepository.delete(cartItem);
        } else {
            cartItem.setQuantity(quantity);
        }

        // Explicit scalar update ensures Cart.version bump
        cart.setUpdatedAt(Instant.now());
        cartRepository.save(cart);
    }

    @Transactional
    public void mutateRemoveCartItem(UUID customerId, UUID itemId) {
        cartItemRepository.findByIdAndCartCustomerId(itemId, customerId)
                .ifPresent(item -> {
                    Cart cart = item.getCart();
                    cart.getItems().remove(item);
                    cart.setUpdatedAt(Instant.now());
                    cartItemRepository.delete(item);
                    cartRepository.save(cart);
                });
    }

    @Transactional
    public void mutateClearCart(UUID customerId) {
        cartRepository.findByCustomerId(customerId).ifPresent(cart -> {
            cart.getItems().clear();
            cart.setUpdatedAt(Instant.now());
            cartItemRepository.deleteAllByCartId(cart.getId());
            cartRepository.save(cart);
        });
    }
}
