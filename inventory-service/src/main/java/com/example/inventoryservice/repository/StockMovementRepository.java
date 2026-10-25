package com.example.inventoryservice.repository;

import com.example.inventoryservice.entity.StockMovement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {

    Page<StockMovement> findBySkuIdAndWarehouseId(UUID skuId, UUID warehouseId, Pageable pageable);

    Optional<StockMovement> findByReferenceTypeAndReferenceIdAndSkuId(String referenceType, String referenceId, UUID skuId);

    boolean existsByReferenceTypeAndReferenceIdAndSkuId(String referenceType, String referenceId, UUID skuId);
}
