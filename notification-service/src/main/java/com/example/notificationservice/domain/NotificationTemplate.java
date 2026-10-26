package com.example.notificationservice.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "notification_templates",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_notification_template_version", columnNames = {"event_type", "channel", "language", "version"})
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationTemplate {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 50)
    @Builder.Default
    private NotificationChannel channel = NotificationChannel.EMAIL;

    @Column(name = "language", nullable = false, length = 10)
    @Builder.Default
    private String language = "vi";

    @Column(name = "subject_template", nullable = false, columnDefinition = "TEXT")
    private String subjectTemplate;

    @Column(name = "body_template", nullable = false, columnDefinition = "TEXT")
    private String bodyTemplate;

    @Column(name = "version", nullable = false)
    @Builder.Default
    private Integer version = 1;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (isActive == null) {
            isActive = true;
        }
        if (channel == null) {
            channel = NotificationChannel.EMAIL;
        }
        if (language == null) {
            language = "vi";
        }
        if (version == null) {
            version = 1;
        }
    }
}
