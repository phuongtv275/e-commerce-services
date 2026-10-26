package com.example.notificationservice.service;

import com.ecommerce.common.idempotency.ProcessedEvent;
import com.example.notificationservice.repository.NotificationProcessedEventRepository;
import com.example.notificationservice.domain.NotificationChannel;
import com.example.notificationservice.domain.NotificationLog;
import com.example.notificationservice.domain.NotificationStatus;
import com.example.notificationservice.domain.NotificationTemplate;
import com.example.notificationservice.metrics.NotificationMetrics;
import com.example.notificationservice.repository.NotificationLogRepository;
import com.example.notificationservice.repository.NotificationTemplateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationIngestionServiceTests {

    @Mock
    private NotificationLogRepository notificationLogRepository;

    @Mock
    private NotificationTemplateRepository templateRepository;

    @Mock
    private NotificationProcessedEventRepository processedEventRepository;

    @Mock
    private TemplateRenderService templateRenderService;

    @Mock
    private NotificationMetrics notificationMetrics;

    private NotificationIngestionService ingestionService;

    @BeforeEach
    void setUp() {
        ingestionService = new NotificationIngestionService(
                notificationLogRepository,
                templateRepository,
                processedEventRepository,
                templateRenderService,
                notificationMetrics,
                new ObjectMapper().findAndRegisterModules()
        );
    }

    @Test
    @DisplayName("Positive: Process and queue notification successfully")
    void testProcessAndQueue_Success() {
        String eventId = UUID.randomUUID().toString();
        String eventType = "user.registered";
        String recipient = "user@example.com";
        Map<String, Object> params = Map.of("fullName", "User A");

        when(notificationLogRepository.existsByEventIdAndChannel(eventId, NotificationChannel.EMAIL)).thenReturn(false);
        when(processedEventRepository.existsByEventIdAndConsumerGroup(eventId, NotificationIngestionService.CONSUMER_GROUP)).thenReturn(false);

        NotificationTemplate template = NotificationTemplate.builder()
                .subjectTemplate("Chào mừng {{fullName}}")
                .bodyTemplate("<p>Xác minh tài khoản</p>")
                .build();
        when(templateRepository.findFirstByEventTypeAndChannelAndLanguageAndIsActiveTrueOrderByVersionDesc(eventType, NotificationChannel.EMAIL, "vi"))
                .thenReturn(Optional.of(template));

        when(templateRenderService.renderSubject(template.getSubjectTemplate(), params)).thenReturn("Chào mừng User A");
        when(templateRenderService.renderBody(template.getBodyTemplate(), params)).thenReturn("<p>Xác minh tài khoản</p>");

        boolean result = ingestionService.processAndQueue(eventId, eventType, "corr-1", recipient, params, Map.of("id", 1));

        assertTrue(result);

        ArgumentCaptor<NotificationLog> logCaptor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository).save(logCaptor.capture());
        NotificationLog savedLog = logCaptor.getValue();
        assertEquals(eventId, savedLog.getEventId());
        assertEquals(eventType, savedLog.getEventType());
        assertEquals(recipient, savedLog.getRecipient());
        assertEquals("Chào mừng User A", savedLog.getSubject());
        assertEquals(NotificationStatus.QUEUED, savedLog.getStatus());

        verify(processedEventRepository).save(any(ProcessedEvent.class));
        verify(notificationMetrics).incrementQueued(eventType);
    }

    @Test
    @DisplayName("Positive: Duplicate event is detected and skipped as no-op")
    void testProcessAndQueue_DuplicateIgnored() {
        String eventId = "evt-dup-123";
        when(notificationLogRepository.existsByEventIdAndChannel(eventId, NotificationChannel.EMAIL)).thenReturn(true);

        boolean result = ingestionService.processAndQueue(eventId, "user.registered", "corr-2", "dup@example.com", Map.of(), null);

        assertTrue(result);
        verify(notificationLogRepository, never()).save(any());
        verify(processedEventRepository, never()).save(any());
        verify(notificationMetrics, never()).incrementQueued(any());
    }

    @Test
    @DisplayName("Negative: Throw exception when eventId is null or blank")
    void testProcessAndQueue_MissingEventId() {
        assertThrows(IllegalArgumentException.class, () ->
                ingestionService.processAndQueue("", "user.registered", "corr", "test@example.com", Map.of(), null)
        );
        assertThrows(IllegalArgumentException.class, () ->
                ingestionService.processAndQueue(null, "user.registered", "corr", "test@example.com", Map.of(), null)
        );
    }

    @Test
    @DisplayName("Negative: Throw exception when recipient is null or blank")
    void testProcessAndQueue_MissingRecipient() {
        assertThrows(IllegalArgumentException.class, () ->
                ingestionService.processAndQueue("evt-1", "user.registered", "corr", "", Map.of(), null)
        );
    }

    @Test
    @DisplayName("Negative: Throw exception when template is not found")
    void testProcessAndQueue_MissingTemplate() {
        String eventId = "evt-no-tpl";
        when(notificationLogRepository.existsByEventIdAndChannel(eventId, NotificationChannel.EMAIL)).thenReturn(false);
        when(processedEventRepository.existsByEventIdAndConsumerGroup(eventId, NotificationIngestionService.CONSUMER_GROUP)).thenReturn(false);
        when(templateRepository.findFirstByEventTypeAndChannelAndLanguageAndIsActiveTrueOrderByVersionDesc(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(templateRepository.findFirstByEventTypeAndChannelAndIsActiveTrueOrderByVersionDesc(any(), any()))
                .thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () ->
                ingestionService.processAndQueue(eventId, "unknown.event", "corr", "user@example.com", Map.of(), null)
        );
    }
}
