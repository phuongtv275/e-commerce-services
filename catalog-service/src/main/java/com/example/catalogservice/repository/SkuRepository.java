package com.example.catalogservice.repository;

import com.example.catalogservice.entity.Sku;
import com.example.catalogservice.entity.SkuStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SkuRepository extends JpaRepository<Sku, UUID> {

    Optional<Sku> findByIdAndDeletedAtIsNull(UUID id);

    Optional<Sku> findBySkuCodeAndDeletedAtIsNull(String skuCode);

    boolean existsBySkuCodeAndDeletedAtIsNull(String skuCode);

    boolean existsBySkuCodeAndIdNotAndDeletedAtIsNull(String skuCode, UUID id);

    List<Sku> findByProductIdAndDeletedAtIsNull(UUID productId);

    List<Sku> findByProductIdAndStatusAndDeletedAtIsNull(UUID productId, SkuStatus status);

    boolean existsByProductIdAndStatusAndDeletedAtIsNull(UUID productId, SkuStatus status);

    List<Sku> findByIdInAndDeletedAtIsNull(Collection<UUID> ids);

    long countByStatusAndDeletedAtIsNull(SkuStatus status);

    @Query("SELECT DISTINCT s.productId FROM Sku s WHERE s.updatedAt >= :since")
    List<UUID> findProductIdsBySkuUpdatedAtGreaterThanEqual(@Param("since") Instant since);
}
