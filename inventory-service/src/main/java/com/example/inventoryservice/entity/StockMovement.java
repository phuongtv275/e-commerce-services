package com.example.inventoryservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "stock_movements", indexes = {
        @Index(name = "idx_stock_movements_sku_time", columnList = "sku_id, occurred_at, id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockMovement {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "sku_id", nullable = false, updatable = false)
    private UUID skuId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false, updatable = false)
    private Warehouse warehouse;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 20, updatable = false)
    private MovementType movementType;

    @Column(name = "quantity_delta", nullable = false, updatable = false)
    private int quantityDelta;

    @Column(name = "reference_type", nullable = false, length = 50, updatable = false)
    private String referenceType;

    @Column(name = "reference_id", nullable = false, length = 255, updatable = false)
    private String referenceId;

    @Column(name = "before_on_hand", nullable = false, updatable = false)
    private int beforeOnHand;

    @Column(name = "after_on_hand", nullable = false, updatable = false)
    private int afterOnHand;

    @Column(name = "before_reserved", nullable = false, updatable = false)
    private int beforeReserved;

    @Column(name = "after_reserved", nullable = false, updatable = false)
    private int afterReserved;

    @Column(name = "actor", length = 255, updatable = false)
    private String actor;

    @Column(name = "reason", length = 500, updatable = false)
    private String reason;

    @Column(name = "correlation_id", length = 255, updatable = false)
    private String correlationId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }
}
