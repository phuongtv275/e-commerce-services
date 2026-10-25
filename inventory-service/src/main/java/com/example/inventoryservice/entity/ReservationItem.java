package com.example.inventoryservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "reservation_items", uniqueConstraints = {
        @UniqueConstraint(name = "uq_reservation_item_sku", columnNames = {"reservation_id", "sku_id"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationItem {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }
}
