package com.example.inventoryservice.repository;

import com.example.inventoryservice.entity.StockMovement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StockMovementRepository extends JpaRepository<StockMovement, UUID>, JpaSpecificationExecutor<StockMovement> {

    Page<StockMovement> findBySkuIdAndWarehouseId(UUID skuId, UUID warehouseId, Pageable pageable);

    List<StockMovement> findByReferenceTypeAndReferenceId(String referenceType, String referenceId);

    Optional<StockMovement> findByReferenceTypeAndReferenceIdAndSkuId(String referenceType, String referenceId, UUID skuId);

    boolean existsByReferenceTypeAndReferenceIdAndSkuId(String referenceType, String referenceId, UUID skuId);

    @org.springframework.data.jpa.repository.Query(
            value = "SELECT coalesce(pg_advisory_xact_lock(hashtext(:refId)::bigint)::text, 'OK')",
            nativeQuery = true)
    String acquireAdvisoryLock(@org.springframework.data.repository.query.Param("refId") String refId);
}
