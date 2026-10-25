package com.example.catalogservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "catalog_reindex_jobs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CatalogReindexJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ReindexJobStatus status;

    @Column(name = "target_index", nullable = false)
    private String targetIndex;

    @Column(name = "total_documents")
    @Builder.Default
    private Long totalDocuments = 0L;

    @Column(name = "processed_documents")
    @Builder.Default
    private Long processedDocuments = 0L;

    @Column(name = "failed_documents")
    @Builder.Default
    private Long failedDocuments = 0L;

    @Column(name = "error_summary", columnDefinition = "TEXT")
    private String errorSummary;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
