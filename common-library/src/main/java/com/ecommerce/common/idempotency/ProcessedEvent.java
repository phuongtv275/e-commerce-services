package com.ecommerce.common.idempotency;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "processed_events",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_event_consumer_group", columnNames = {"event_id", "consumer_group"})
        },
        indexes = {
                @Index(name = "idx_processed_event_lookup", columnList = "event_id, consumer_group")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false, length = 100)
    private String eventId;

    @Column(name = "consumer_group", nullable = false, length = 100)
    private String consumerGroup;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (processedAt == null) {
            processedAt = Instant.now();
        }
    }
}
