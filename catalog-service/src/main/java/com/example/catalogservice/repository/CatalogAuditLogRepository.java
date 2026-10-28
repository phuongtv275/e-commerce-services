package com.example.catalogservice.repository;

import com.example.catalogservice.entity.CatalogAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface CatalogAuditLogRepository extends JpaRepository<CatalogAuditLog, UUID> {

    Page<CatalogAuditLog> findAll(Pageable pageable);

    Page<CatalogAuditLog> findAllByOrderByOccurredAtDesc(Pageable pageable);
}
