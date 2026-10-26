package com.example.notificationservice.service;

import com.ecommerce.common.idempotency.ProcessedEvent;
import com.ecommerce.common.idempotency.ProcessedStatus;
import com.example.notificationservice.domain.NotificationChannel;
import com.example.notificationservice.domain.NotificationLog;
import com.example.notificationservice.domain.NotificationStatus;
import com.example.notificationservice.domain.NotificationTemplate;
import com.example.notificationservice.metrics.NotificationMetrics;
import com.example.notificationservice.repository.NotificationLogRepository;
import com.example.notificationservice.repository.NotificationProcessedEventRepository;
import com.example.notificationservice.repository.NotificationTemplateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationIngestionService {

    public static final String CONSUMER_GROUP = "notification-service-group";

    private final NotificationLogRepository notificationLogRepository;
    private final NotificationTemplateRepository templateRepository;
    private final NotificationProcessedEventRepository processedEventRepository;
    private final TemplateRenderService templateRenderService;
    private final NotificationMetrics notificationMetrics;
    private final ObjectMapper objectMapper;

    @Transactional
    public boolean processAndQueue(
            String eventId,
            String eventType,
            String correlationId,
            String recipient,
            Map<String, Object> params,
            Object payload
    ) {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId cannot be null or blank");
        }
        if (recipient == null || recipient.isBlank()) {
            throw new IllegalArgumentException("recipient cannot be null or blank");
        }

        // 1. Idempotency Check
        if (notificationLogRepository.existsByEventIdAndChannel(eventId, NotificationChannel.EMAIL)
                || processedEventRepository.existsByEventIdAndConsumerGroup(eventId, CONSUMER_GROUP)) {
            log.info("Event {} already processed for group {}, skipping duplicate", eventId, CONSUMER_GROUP);
            return true;
        }

        // 2. Load Template
        Optional<NotificationTemplate> templateOpt = templateRepository
                .findFirstByEventTypeAndChannelAndLanguageAndIsActiveTrueOrderByVersionDesc(
                        eventType,
                        NotificationChannel.EMAIL,
                        "vi"
                );

        if (templateOpt.isEmpty()) {
            templateOpt = templateRepository.findFirstByEventTypeAndChannelAndIsActiveTrueOrderByVersionDesc(
                    eventType,
                    NotificationChannel.EMAIL
            );
        }

        if (templateOpt.isEmpty()) {
            log.error("No active template found for eventType: {}", eventType);
            throw new IllegalStateException("No active email template found for eventType: " + eventType);
        }

        NotificationTemplate template = templateOpt.get();

        // 3. Render Subject & Body
        String renderedSubject = templateRenderService.renderSubject(template.getSubjectTemplate(), params);
        String renderedBody = templateRenderService.renderBody(template.getBodyTemplate(), params);

        String bodyPreview = renderedBody.length() > 200 ? renderedBody.substring(0, 200) : renderedBody;

        // 4. Serialize Payload safely
        String payloadJson = null;
        if (payload != null) {
            try {
                payloadJson = objectMapper.writeValueAsString(payload);
            } catch (Exception e) {
                log.warn("Failed to serialize payload to JSON: {}", e.getMessage());
            }
        }

        Instant now = Instant.now();

        // 5. Create NotificationLog
        NotificationLog notificationLog = NotificationLog.builder()
                .id(UUID.randomUUID())
                .eventId(eventId)
                .eventType(eventType)
                .channel(NotificationChannel.EMAIL)
                .recipient(recipient)
                .subject(renderedSubject)
                .renderedBody(renderedBody)
                .bodyPreview(bodyPreview)
                .payload(payloadJson)
                .status(NotificationStatus.QUEUED)
                .retryCount(0)
                .correlationId(correlationId)
                .queuedAt(now)
                .updatedAt(now)
                .build();

        notificationLogRepository.save(notificationLog);

        // 6. Record ProcessedEvent
        ProcessedEvent processedEvent = ProcessedEvent.builder()
                .id(UUID.randomUUID())
                .eventId(eventId)
                .consumerGroup(CONSUMER_GROUP)
                .status(ProcessedStatus.COMPLETED)
                .processedAt(now)
                .build();

        processedEventRepository.save(processedEvent);

        notificationMetrics.incrementQueued(eventType);
        log.info("Notification job successfully queued: id={}, eventId={}, eventType={}",
                notificationLog.getId(), eventId, eventType);

        return true;
    }
}
