package com.example.notificationservice.dto;

import com.example.notificationservice.domain.NotificationChannel;
import com.example.notificationservice.domain.NotificationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationDetailResponse {
    private UUID id;
    private String eventId;
    private String eventType;
    private NotificationChannel channel;
    private String recipient;
    private String subject;
    private String renderedBody;
    private String bodyPreview;
    private String payload;
    private NotificationStatus status;
    private int retryCount;
    private String errorMessage;
    private String correlationId;
    private Instant queuedAt;
    private Instant sentAt;
    private Instant updatedAt;
}
