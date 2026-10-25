package com.example.orderservice.controller;

import com.ecommerce.common.response.ApiResponse;
import com.example.orderservice.dto.AddToCartRequest;
import com.example.orderservice.dto.CartResponse;
import com.example.orderservice.dto.UpdateCartItemRequest;
import com.example.orderservice.security.SecurityUtils;
import com.example.orderservice.service.CartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @GetMapping
    public ResponseEntity<ApiResponse<CartResponse>> getCart() {
        UUID customerId = SecurityUtils.getRequiredCurrentCustomerId();
        CartResponse response = cartService.getCart(customerId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/items")
    public ResponseEntity<ApiResponse<CartResponse>> addToCart(@Valid @RequestBody AddToCartRequest request) {
        UUID customerId = SecurityUtils.getRequiredCurrentCustomerId();
        CartResponse response = cartService.addToCart(customerId, request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PutMapping("/items/{itemId}")
    public ResponseEntity<ApiResponse<CartResponse>> updateCartItem(
            @PathVariable UUID itemId,
            @Valid @RequestBody UpdateCartItemRequest request) {
        UUID customerId = SecurityUtils.getRequiredCurrentCustomerId();
        CartResponse response = cartService.updateCartItem(customerId, itemId, request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @DeleteMapping("/items/{itemId}")
    public ResponseEntity<Void> removeCartItem(@PathVariable UUID itemId) {
        UUID customerId = SecurityUtils.getRequiredCurrentCustomerId();
        cartService.removeCartItem(customerId, itemId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> clearCart() {
        UUID customerId = SecurityUtils.getRequiredCurrentCustomerId();
        cartService.clearCart(customerId);
        return ResponseEntity.noContent().build();
    }
}
