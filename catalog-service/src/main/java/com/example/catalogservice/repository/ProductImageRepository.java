package com.example.catalogservice.repository;

import com.example.catalogservice.entity.ProductImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProductImageRepository extends JpaRepository<ProductImage, UUID> {

    List<ProductImage> findByProductIdOrderByDisplayOrderAscCreatedAtAsc(UUID productId);

    List<ProductImage> findByProductIdInOrderByDisplayOrderAscCreatedAtAsc(Collection<UUID> productIds);

    Optional<ProductImage> findByProductIdAndPrimaryTrue(UUID productId);

    Optional<ProductImage> findByIdAndProductId(UUID id, UUID productId);

    @Modifying
    @Query("UPDATE ProductImage pi SET pi.primary = false WHERE pi.productId = :productId")
    void resetPrimaryForProduct(@Param("productId") UUID productId);
}
