package com.example.notificationservice.service;

import com.example.notificationservice.domain.NotificationLog;
import com.example.notificationservice.domain.NotificationStatus;
import com.example.notificationservice.metrics.NotificationMetrics;
import com.example.notificationservice.repository.NotificationLogRepository;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MailDeliveryWorkerTests {

    @Mock
    private NotificationLogRepository notificationLogRepository;

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private NotificationMetrics notificationMetrics;

    private MailDeliveryWorker worker;

    @BeforeEach
    void setUp() {
        worker = new MailDeliveryWorker(
                notificationLogRepository,
                mailSender,
                notificationMetrics,
                5
        );
        ReflectionTestUtils.setField(worker, "mailFrom", "noreply@ecom.local");
        ReflectionTestUtils.setField(worker, "claimLeaseMinutes", 10L);
        ReflectionTestUtils.setField(worker, "initialBackoffMs", 1000L);
        ReflectionTestUtils.setField(worker, "maxAttempts", 3);

        MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
        lenient().when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
    }

    @Test
    @DisplayName("Positive: Successfully send email and transition job to SENT")
    void testProcessCandidate_Success() {
        UUID id = UUID.randomUUID();
        NotificationLog job = NotificationLog.builder()
                .id(id)
                .recipient("test@example.com")
                .subject("Test Subject")
                .renderedBody("<p>Hello</p>")
                .status(NotificationStatus.QUEUED)
                .retryCount(0)
                .eventType("order.status-changed")
                .build();

        when(notificationLogRepository.claimNotification(eq(id), any(), any(), any(), any())).thenReturn(1);
        when(notificationLogRepository.findById(id)).thenReturn(Optional.of(job));

        worker.processCandidate(id);

        verify(mailSender).send(any(MimeMessage.class));
        verify(notificationLogRepository).updateExecutionResult(
                eq(id),
                any(UUID.class),
                eq(NotificationStatus.SENT),
                any(Instant.class),
                isNull(),
                eq(0),
                isNull(),
                isNull(),
                any(Instant.class)
        );
        verify(notificationMetrics).incrementSent("order.status-changed");
    }

    @Test
    @DisplayName("Negative: SMTP transient failure sets backoff and status FAILED")
    void testProcessCandidate_TransientFailure() {
        UUID id = UUID.randomUUID();
        NotificationLog job = NotificationLog.builder()
                .id(id)
                .recipient("test@example.com")
                .subject("Test Subject")
                .renderedBody("<p>Hello</p>")
                .status(NotificationStatus.QUEUED)
                .retryCount(0)
                .eventType("order.status-changed")
                .build();

        when(notificationLogRepository.claimNotification(eq(id), any(), any(), any(), any())).thenReturn(1);
        when(notificationLogRepository.findById(id)).thenReturn(Optional.of(job));
        doThrow(new MailSendException("SMTP connection timed out")).when(mailSender).send(any(MimeMessage.class));

        worker.processCandidate(id);

        verify(notificationLogRepository).updateExecutionResult(
                eq(id),
                any(UUID.class),
                eq(NotificationStatus.FAILED),
                isNull(),
                contains("SMTP connection timed out"),
                eq(1),
                notNull(),
                isNull(),
                any(Instant.class)
        );
        verify(notificationMetrics).incrementFailed("order.status-changed");
    }

    @Test
    @DisplayName("Negative: Retry count reaches maxAttempts transitions job to DEAD_LETTER")
    void testProcessCandidate_ExhaustedRetriesTransitionsToDeadLetter() {
        UUID id = UUID.randomUUID();
        NotificationLog job = NotificationLog.builder()
                .id(id)
                .recipient("test@example.com")
                .subject("Test Subject")
                .renderedBody("<p>Hello</p>")
                .status(NotificationStatus.FAILED)
                .retryCount(2) // Next will be attempt 3 >= maxAttempts 3
                .eventType("user.registered")
                .build();

        when(notificationLogRepository.claimNotification(eq(id), any(), any(), any(), any())).thenReturn(1);
        when(notificationLogRepository.findById(id)).thenReturn(Optional.of(job));
        doThrow(new MailSendException("SMTP refusal")).when(mailSender).send(any(MimeMessage.class));

        worker.processCandidate(id);

        verify(notificationLogRepository).updateExecutionResult(
                eq(id),
                any(UUID.class),
                eq(NotificationStatus.DEAD_LETTER),
                isNull(),
                contains("SMTP refusal"),
                eq(3),
                isNull(),
                isNull(),
                any(Instant.class)
        );
        verify(notificationMetrics).incrementDlq("user.registered");
    }

    @Test
    @DisplayName("Negative: Another worker already claimed the record (fencing)")
    void testProcessCandidate_AlreadyClaimed() {
        UUID id = UUID.randomUUID();
        when(notificationLogRepository.claimNotification(eq(id), any(), any(), any(), any())).thenReturn(0);

        worker.processCandidate(id);

        verify(mailSender, never()).send(any(MimeMessage.class));
        verify(notificationLogRepository, never()).updateExecutionResult(any(), any(), any(), any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("Positive: Graceful shutdown terminates workerPool safely")
    void testShutdown_TerminatesSafely() {
        assertDoesNotThrow(() -> worker.shutdown());
        ExecutorService pool = (ExecutorService) ReflectionTestUtils.getField(worker, "workerPool");
        assertNotNull(pool);
        assertTrue(pool.isShutdown());
    }
}
