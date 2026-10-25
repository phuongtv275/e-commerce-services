package com.example.catalogservice.dto.resp;

import com.example.catalogservice.entity.ReindexJobStatus;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReindexJobResponse {

    private UUID id;

    private ReindexJobStatus status;

    private String targetIndex;

    private Long totalDocuments;

    private Long processedDocuments;

    private Long failedDocuments;

    private String errorSummary;

    private Instant startedAt;

    private Instant finishedAt;

    private Instant createdAt;
}
