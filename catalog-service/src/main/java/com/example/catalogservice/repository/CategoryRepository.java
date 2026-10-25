package com.example.catalogservice.repository;

import com.example.catalogservice.entity.Category;
import com.example.catalogservice.entity.CategoryStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CategoryRepository extends JpaRepository<Category, UUID> {

    Optional<Category> findBySlug(String slug);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, UUID id);

    List<Category> findByStatusOrderByDisplayOrderAscNameAscIdAsc(CategoryStatus status);

    List<Category> findByParentId(UUID parentId);

    boolean existsByParentId(UUID parentId);

    List<Category> findAllByOrderByDisplayOrderAscNameAscIdAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Category c WHERE c.id = :id")
    Optional<Category> findByIdForUpdate(@Param("id") UUID id);

    List<Category> findByUpdatedAtGreaterThanEqual(java.time.Instant since);
}
