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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DlqRedriveWorkerTests {

    @Mock
    private NotificationLogRepository notificationLogRepository;

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private NotificationMetrics notificationMetrics;

    private DlqRedriveWorker redriveWorker;

    @BeforeEach
    void setUp() {
        redriveWorker = new DlqRedriveWorker(
                notificationLogRepository,
                mailSender,
                notificationMetrics
        );
        ReflectionTestUtils.setField(redriveWorker, "mailFrom", "noreply@ecom.local");
        ReflectionTestUtils.setField(redriveWorker, "cooldownMinutes", 5L);
        ReflectionTestUtils.setField(redriveWorker, "leaseExpiryMinutes", 10L);
        ReflectionTestUtils.setField(redriveWorker, "batchSize", 50);
        ReflectionTestUtils.setField(redriveWorker, "maxTotalAttempts", 10);

        MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
        lenient().when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
    }

    @Test
    @DisplayName("Positive: Redrive DEAD_LETTER candidate successfully to SENT")
    void testRedriveSingle_Success() {
        UUID id = UUID.randomUUID();
        NotificationLog job = NotificationLog.builder()
                .id(id)
                .recipient("redrive@example.com")
                .subject("Redrive Subject")
                .renderedBody("<p>Redrive Content</p>")
                .status(NotificationStatus.DEAD_LETTER)
                .retryCount(3)
                .eventType("order.cancelled")
                .build();

        when(notificationLogRepository.claimForRedrive(eq(id), any(), any(), any(), eq(10), any())).thenReturn(1);
        when(notificationLogRepository.findById(id)).thenReturn(Optional.of(job));

        boolean result = redriveWorker.redriveSingle(id);

        assertTrue(result);
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
        verify(notificationMetrics).incrementRedrive("order.cancelled");
        verify(notificationMetrics).incrementSent("order.cancelled");
    }

    @Test
    @DisplayName("Negative: Redrive fails if cannot claim record (not eligible or claimed)")
    void testRedriveSingle_ClaimFailed() {
        UUID id = UUID.randomUUID();
        when(notificationLogRepository.claimForRedrive(eq(id), any(), any(), any(), eq(10), any())).thenReturn(0);

        boolean result = redriveWorker.redriveSingle(id);

        assertFalse(result);
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("Negative: Redrive delivery fails returns job to DEAD_LETTER")
    void testRedriveSingle_DeliveryFailure() {
        UUID id = UUID.randomUUID();
        NotificationLog job = NotificationLog.builder()
                .id(id)
                .recipient("redrive@example.com")
                .subject("Redrive Subject")
                .renderedBody("<p>Redrive Content</p>")
                .status(NotificationStatus.DEAD_LETTER)
                .retryCount(3)
                .eventType("order.cancelled")
                .build();

        when(notificationLogRepository.claimForRedrive(eq(id), any(), any(), any(), eq(10), any())).thenReturn(1);
        when(notificationLogRepository.findById(id)).thenReturn(Optional.of(job));
        doThrow(new MailSendException("SMTP host down")).when(mailSender).send(any(MimeMessage.class));

        boolean result = redriveWorker.redriveSingle(id);

        assertFalse(result);
        verify(notificationLogRepository).updateExecutionResult(
                eq(id),
                any(UUID.class),
                eq(NotificationStatus.DEAD_LETTER),
                isNull(),
                contains("SMTP host down"),
                eq(4),
                isNull(),
                isNull(),
                any(Instant.class)
        );
        verify(notificationMetrics).incrementDlq("order.cancelled");
    }
}
