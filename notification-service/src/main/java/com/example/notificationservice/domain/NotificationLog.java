package com.example.notificationservice.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "notification_logs",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_notification_event_channel", columnNames = {"event_id", "channel"})
        },
        indexes = {
                @Index(name = "idx_notification_logs_status_next_attempt", columnList = "status, next_attempt_at"),
                @Index(name = "idx_notification_logs_queued_at", columnList = "queued_at"),
                @Index(name = "idx_notification_logs_status_updated", columnList = "status, updated_at")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationLog {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false, length = 255)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 50)
    @Builder.Default
    private NotificationChannel channel = NotificationChannel.EMAIL;

    @Column(name = "recipient", nullable = false, length = 500)
    private String recipient;

    @Column(name = "subject", nullable = false, columnDefinition = "TEXT")
    private String subject;

    @Column(name = "rendered_body", nullable = false, columnDefinition = "TEXT")
    private String renderedBody;

    @Column(name = "body_preview", columnDefinition = "TEXT")
    private String bodyPreview;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private NotificationStatus status = NotificationStatus.QUEUED;

    @Column(name = "provider_message_id", length = 255)
    private String providerMessageId;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "correlation_id", length = 255)
    private String correlationId;

    @Column(name = "queued_at", nullable = false, updatable = false)
    private Instant queuedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "claim_token")
    private UUID claimToken;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (channel == null) {
            channel = NotificationChannel.EMAIL;
        }
        if (status == null) {
            status = NotificationStatus.QUEUED;
        }
        if (retryCount == null) {
            retryCount = 0;
        }
        Instant now = Instant.now();
        if (queuedAt == null) {
            queuedAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
