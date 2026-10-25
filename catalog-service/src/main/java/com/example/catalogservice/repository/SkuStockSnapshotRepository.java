package com.example.catalogservice.repository;

import com.example.catalogservice.entity.SkuStockSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface SkuStockSnapshotRepository extends JpaRepository<SkuStockSnapshot, UUID> {
}
