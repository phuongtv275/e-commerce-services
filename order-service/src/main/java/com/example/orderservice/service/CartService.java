package com.example.orderservice.service;

import com.example.orderservice.dto.AddToCartRequest;
import com.example.orderservice.dto.CartResponse;
import com.example.orderservice.dto.UpdateCartItemRequest;

import java.util.UUID;

public interface CartService {

    CartResponse getCart(UUID customerId);

    CartResponse addToCart(UUID customerId, AddToCartRequest request);

    CartResponse updateCartItem(UUID customerId, UUID itemId, UpdateCartItemRequest request);

    void removeCartItem(UUID customerId, UUID itemId);

    void clearCart(UUID customerId);
}
