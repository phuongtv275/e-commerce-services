package com.example.inventoryservice.repository;

import com.example.inventoryservice.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StockRepository extends JpaRepository<Stock, UUID> {

    Optional<Stock> findBySkuIdAndWarehouseId(UUID skuId, UUID warehouseId);

    List<Stock> findBySkuIdInAndWarehouseId(List<UUID> skuIds, UUID warehouseId);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE stocks
            SET reserved = reserved + :quantity,
                version = version + 1,
                updated_at = :now
            WHERE sku_id = :skuId
              AND warehouse_id = :warehouseId
              AND on_hand - reserved >= :quantity
            """, nativeQuery = true)
    int reserveStock(@Param("skuId") UUID skuId,
                     @Param("warehouseId") UUID warehouseId,
                     @Param("quantity") int quantity,
                     @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE stocks
            SET reserved = reserved - :quantity,
                version = version + 1,
                updated_at = :now
            WHERE sku_id = :skuId
              AND warehouse_id = :warehouseId
              AND reserved >= :quantity
            """, nativeQuery = true)
    int releaseReservedStock(@Param("skuId") UUID skuId,
                             @Param("warehouseId") UUID warehouseId,
                             @Param("quantity") int quantity,
                             @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE stocks
            SET on_hand = on_hand - :quantity,
                reserved = reserved - :quantity,
                version = version + 1,
                updated_at = :now
            WHERE sku_id = :skuId
              AND warehouse_id = :warehouseId
              AND on_hand >= :quantity
              AND reserved >= :quantity
            """, nativeQuery = true)
    int commitStock(@Param("skuId") UUID skuId,
                    @Param("warehouseId") UUID warehouseId,
                    @Param("quantity") int quantity,
                    @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE stocks
            SET on_hand = on_hand + :quantity,
                version = version + 1,
                updated_at = :now
            WHERE sku_id = :skuId
              AND warehouse_id = :warehouseId
            """, nativeQuery = true)
    int restockStock(@Param("skuId") UUID skuId,
                     @Param("warehouseId") UUID warehouseId,
                     @Param("quantity") int quantity,
                     @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE stocks
            SET on_hand = on_hand + :quantityDelta,
                version = version + 1,
                updated_at = :now
            WHERE sku_id = :skuId
              AND warehouse_id = :warehouseId
              AND on_hand + :quantityDelta >= reserved
              AND on_hand + :quantityDelta >= 0
            """, nativeQuery = true)
    int adjustStock(@Param("skuId") UUID skuId,
                    @Param("warehouseId") UUID warehouseId,
                    @Param("quantityDelta") int quantityDelta,
                    @Param("now") Instant now);
}
