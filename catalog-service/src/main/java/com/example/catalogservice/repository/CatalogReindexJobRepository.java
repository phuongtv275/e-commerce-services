package com.example.catalogservice.repository;

import com.example.catalogservice.entity.CatalogReindexJob;
import com.example.catalogservice.entity.ReindexJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface CatalogReindexJobRepository extends JpaRepository<CatalogReindexJob, UUID> {

    List<CatalogReindexJob> findByStatus(ReindexJobStatus status);

    List<CatalogReindexJob> findByStatusIn(Collection<ReindexJobStatus> statuses);
}
