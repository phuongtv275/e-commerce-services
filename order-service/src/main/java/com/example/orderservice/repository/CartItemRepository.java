package com.example.orderservice.repository;

import com.example.orderservice.entity.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CartItemRepository extends JpaRepository<CartItem, UUID> {

    @Query("SELECT ci FROM CartItem ci WHERE ci.id = :id AND ci.cart.customerId = :customerId")
    Optional<CartItem> findByIdAndCartCustomerId(@Param("id") UUID id, @Param("customerId") UUID customerId);

    @Modifying
    @Query("DELETE FROM CartItem ci WHERE ci.cart.id = :cartId")
    void deleteAllByCartId(@Param("cartId") UUID cartId);
}
