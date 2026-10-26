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
public class NotificationSummaryResponse {
    private UUID id;
    private String eventId;
    private String eventType;
    private NotificationChannel channel;
    private String maskedRecipient;
    private String subject;
    private int retryCount;
    private NotificationStatus status;
    private String errorMessage;
    private Instant queuedAt;
    private Instant updatedAt;
    private String correlationId;
}
