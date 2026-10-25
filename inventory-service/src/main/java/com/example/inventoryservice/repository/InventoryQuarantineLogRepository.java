package com.example.inventoryservice.repository;

import com.example.inventoryservice.entity.InventoryQuarantineLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface InventoryQuarantineLogRepository extends JpaRepository<InventoryQuarantineLog, UUID> {
}
