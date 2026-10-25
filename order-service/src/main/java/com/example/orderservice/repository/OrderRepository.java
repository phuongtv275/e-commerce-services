package com.example.orderservice.repository;

import com.example.orderservice.entity.Order;
import com.example.orderservice.entity.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByIdAndCustomerId(UUID id, UUID customerId);

    Page<Order> findByCustomerId(UUID customerId, Pageable pageable);

    Page<Order> findByCustomerIdAndStatus(UUID customerId, OrderStatus status, Pageable pageable);

    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.items LEFT JOIN FETCH o.statusHistories WHERE o.id = :id AND o.customerId = :customerId")
    Optional<Order> findByIdAndCustomerIdWithDetails(@Param("id") UUID id, @Param("customerId") UUID customerId);

    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.items LEFT JOIN FETCH o.statusHistories WHERE o.id = :id")
    Optional<Order> findByIdWithDetails(@Param("id") UUID id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Order o SET o.status = :newStatus, o.cancelReason = :cancelReason, o.cancelledAt = :cancelledAt, o.updatedAt = :now WHERE o.id = :id AND o.status = :expectedStatus")
    int transitionOrderStatus(
            @Param("id") UUID id,
            @Param("expectedStatus") OrderStatus expectedStatus,
            @Param("newStatus") OrderStatus newStatus,
            @Param("cancelReason") String cancelReason,
            @Param("cancelledAt") Instant cancelledAt,
            @Param("now") Instant now
    );
}
