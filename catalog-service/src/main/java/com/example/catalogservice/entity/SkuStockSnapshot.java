package com.example.catalogservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sku_stock_snapshots")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SkuStockSnapshot {

    @Id
    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Column(name = "in_stock", nullable = false)
    private Boolean inStock;

    @Column(name = "inventory_version", nullable = false)
    private Long inventoryVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
