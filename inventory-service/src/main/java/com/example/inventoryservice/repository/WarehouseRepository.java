package com.example.inventoryservice.repository;

import com.example.inventoryservice.entity.Warehouse;
import com.example.inventoryservice.entity.WarehouseStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WarehouseRepository extends JpaRepository<Warehouse, UUID> {
    Optional<Warehouse> findByCode(String code);
    Optional<Warehouse> findFirstByIsDefaultTrueAndStatus(WarehouseStatus status);
}
