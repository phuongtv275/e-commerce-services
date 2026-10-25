package com.example.catalogservice.repository;

import com.example.catalogservice.entity.Product;
import com.example.catalogservice.entity.ProductStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProductRepository extends JpaRepository<Product, UUID> {

    boolean existsByCategoryIdAndDeletedAtIsNull(UUID categoryId);

    boolean existsByCategoryIdAndStatusAndDeletedAtIsNull(UUID categoryId, ProductStatus status);

    boolean existsByBrandIdAndDeletedAtIsNull(UUID brandId);

    long countByCategoryIdAndDeletedAtIsNull(UUID categoryId);

    long countByBrandIdAndDeletedAtIsNull(UUID brandId);

    Optional<Product> findByIdAndDeletedAtIsNull(UUID id);

    List<Product> findByCategoryIdAndDeletedAtIsNull(UUID categoryId);

    List<Product> findByCategoryIdAndDeletedAtIsNullOrderByIdAsc(UUID categoryId, Pageable pageable);

    List<Product> findByBrandIdAndDeletedAtIsNull(UUID brandId);

    List<Product> findByBrandIdAndDeletedAtIsNullOrderByIdAsc(UUID brandId, Pageable pageable);

    List<Product> findAllByDeletedAtIsNullOrderByCreatedAtDesc();

    List<Product> findByStatusAndDeletedAtIsNull(ProductStatus status);

    List<Product> findByDeletedAtIsNullOrderByIdAsc(Pageable pageable);

    List<Product> findByIdGreaterThanAndDeletedAtIsNullOrderByIdAsc(UUID id, Pageable pageable);

    List<Product> findByUpdatedAtGreaterThanEqual(Instant since);
}
