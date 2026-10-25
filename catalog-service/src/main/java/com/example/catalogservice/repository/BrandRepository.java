package com.example.catalogservice.repository;

import com.example.catalogservice.entity.Brand;
import com.example.catalogservice.entity.BrandStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BrandRepository extends JpaRepository<Brand, UUID> {

    Optional<Brand> findBySlug(String slug);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, UUID id);

    List<Brand> findByStatusOrderByNameAsc(BrandStatus status);

    List<Brand> findAllByOrderByNameAsc();

    List<Brand> findByUpdatedAtGreaterThanEqual(java.time.Instant since);
}
