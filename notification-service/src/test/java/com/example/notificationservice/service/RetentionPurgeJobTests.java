package com.example.notificationservice.service;

import com.example.notificationservice.domain.NotificationLog;
import com.example.notificationservice.domain.NotificationStatus;
import com.example.notificationservice.repository.NotificationLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RetentionPurgeJobTests {

    @Mock
    private NotificationLogRepository notificationLogRepository;

    private RetentionPurgeJob purgeJob;

    @BeforeEach
    void setUp() {
        purgeJob = new RetentionPurgeJob(notificationLogRepository);
        ReflectionTestUtils.setField(purgeJob, "retentionDays", 30L);
    }

    @Test
    @DisplayName("Positive: Scrub PII for expired SENT notifications while preserving event ID and audit trail")
    void testPurgeOldRecords_SentNotification() {
        UUID id = UUID.randomUUID();
        NotificationLog oldSent = NotificationLog.builder()
                .id(id)
                .eventId("evt-old-1")
                .recipient("sensitive.user@example.com")
                .subject("Nhạy cảm: Đơn hàng #123")
                .renderedBody("<p>Thông tin thanh toán bí mật</p>")
                .bodyPreview("Thông tin...")
                .payload("{\"secret\":123}")
                .errorMessage("No error")
                .status(NotificationStatus.SENT)
                .sentAt(Instant.now().minusSeconds(86400 * 35))
                .queuedAt(Instant.now().minusSeconds(86400 * 35))
                .updatedAt(Instant.now().minusSeconds(86400 * 35))
                .build();

        when(notificationLogRepository.findRetentionPurgeCandidates(any(), any()))
                .thenReturn(List.of(oldSent))
                .thenReturn(Collections.emptyList());

        int purgedCount = purgeJob.purgeOldRecords();

        assertEquals(1, purgedCount);
        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository).save(captor.capture());
        NotificationLog saved = captor.getValue();

        assertEquals("[PURGED_PII]", saved.getSubject());
        assertEquals("[PURGED_PII]", saved.getRenderedBody());
        assertEquals("[PURGED_PII]", saved.getBodyPreview());
        assertNull(saved.getPayload());
        assertEquals("[PURGED_PII_LOG]", saved.getErrorMessage());
        assertEquals("s***r@example.com", saved.getRecipient());
        assertEquals(NotificationStatus.SENT, saved.getStatus());
        assertEquals("evt-old-1", saved.getEventId());
    }

    @Test
    @DisplayName("Positive: Stale QUEUED notifications older than 30 days are converted to EXPIRED_UNSENT")
    void testPurgeOldRecords_QueuedNotificationBecomesExpiredUnsent() {
        UUID id = UUID.randomUUID();
        NotificationLog oldQueued = NotificationLog.builder()
                .id(id)
                .eventId("evt-stale-queue")
                .recipient("old.queued@example.com")
                .subject("Old Queued Email")
                .renderedBody("<p>Unsent body</p>")
                .status(NotificationStatus.QUEUED)
                .queuedAt(Instant.now().minusSeconds(86400 * 40))
                .updatedAt(Instant.now().minusSeconds(86400 * 40))
                .build();

        when(notificationLogRepository.findRetentionPurgeCandidates(any(), any()))
                .thenReturn(List.of(oldQueued))
                .thenReturn(Collections.emptyList());

        int purged = purgeJob.purgeOldRecords();

        assertEquals(1, purged);
        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository).save(captor.capture());
        NotificationLog saved = captor.getValue();

        assertEquals(NotificationStatus.EXPIRED_UNSENT, saved.getStatus());
        assertEquals("[PURGED_PII]", saved.getSubject());
        assertEquals("o***d@example.com", saved.getRecipient());
    }
}
