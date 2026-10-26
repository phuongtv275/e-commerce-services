package com.example.notificationservice.service;

import com.example.notificationservice.domain.NotificationLog;
import com.example.notificationservice.domain.NotificationStatus;
import com.example.notificationservice.metrics.NotificationMetrics;
import com.example.notificationservice.repository.NotificationLogRepository;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

@Slf4j
@Service
public class MailDeliveryWorker {

    private final NotificationLogRepository notificationLogRepository;
    private final JavaMailSender mailSender;
    private final NotificationMetrics notificationMetrics;
    private final ExecutorService workerPool;

    @Value("${notification.mail.from:${MAIL_FROM:noreply@ecom.local}}")
    private String mailFrom;

    @Value("${notification.worker.claim-lease-minutes:10}")
    private long claimLeaseMinutes;

    @Value("${notification.worker.initial-backoff-ms:1000}")
    private long initialBackoffMs;

    @Value("${notification.worker.max-attempts:3}")
    private int maxAttempts;

    public MailDeliveryWorker(
            NotificationLogRepository notificationLogRepository,
            JavaMailSender mailSender,
            NotificationMetrics notificationMetrics,
            @Value("${notification.worker.concurrency:5}") int maxConcurrency
    ) {
        this.notificationLogRepository = notificationLogRepository;
        this.mailSender = mailSender;
        this.notificationMetrics = notificationMetrics;
        int concurrency = Math.max(1, maxConcurrency);
        this.workerPool = new ThreadPoolExecutor(
                concurrency,
                concurrency,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    @Scheduled(fixedDelayString = "${notification.worker.poll-interval-ms:5000}")
    public void pollAndDeliver() {
        try {
            Instant now = Instant.now();
            Instant leaseCutoff = now.minus(Duration.ofMinutes(claimLeaseMinutes));

            List<NotificationLog> candidates = notificationLogRepository.findSendCandidates(
                    Set.of(NotificationStatus.QUEUED, NotificationStatus.FAILED),
                    now,
                    maxAttempts,
                    leaseCutoff,
                    PageRequest.of(0, 20)
            );

            if (candidates.isEmpty()) {
                return;
            }

            for (NotificationLog candidate : candidates) {
                workerPool.submit(() -> processCandidate(candidate.getId()));
            }
        } catch (Exception e) {
            log.error("Error in mail worker poll cycle: {}", e.getMessage(), e);
        }
    }

    public void processCandidate(UUID notificationId) {
        UUID claimToken = UUID.randomUUID();
        Instant now = Instant.now();
        Instant leaseCutoff = now.minus(Duration.ofMinutes(claimLeaseMinutes));

        // 1. Atomic claim
        int claimed = claimRecord(notificationId, claimToken, leaseCutoff, now);
        if (claimed == 0) {
            // Another worker claimed this job
            return;
        }

        NotificationLog job = notificationLogRepository.findById(notificationId).orElse(null);
        if (job == null) {
            return;
        }

        // 2. Perform SMTP send OUTSIDE database transaction
        try {
            sendEmail(job.getRecipient(), job.getSubject(), job.getRenderedBody());

            // 3. Update Success
            updateSuccess(job.getId(), claimToken);
            notificationMetrics.incrementSent(job.getEventType());
            log.info("Successfully sent email for notification {}", job.getId());
        } catch (Exception e) {
            log.error("Failed to deliver email for notification {}: {}", job.getId(), e.getMessage());
            handleFailure(job, claimToken, e.getMessage());
        }
    }

    @Transactional
    public int claimRecord(UUID id, UUID claimToken, Instant leaseCutoff, Instant now) {
        return notificationLogRepository.claimNotification(
                id,
                claimToken,
                leaseCutoff,
                Set.of(NotificationStatus.QUEUED, NotificationStatus.FAILED),
                now
        );
    }

    private void sendEmail(String recipient, String subject, String htmlBody) throws Exception {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(mailFrom);
        helper.setTo(recipient);
        helper.setSubject(subject);
        helper.setText(htmlBody, true);
        mailSender.send(message);
    }

    @Transactional
    public void updateSuccess(UUID id, UUID claimToken) {
        Instant now = Instant.now();
        notificationLogRepository.updateExecutionResult(
                id,
                claimToken,
                NotificationStatus.SENT,
                now,
                null,
                0,
                null,
                null,
                now
        );
    }

    @Transactional
    public void handleFailure(NotificationLog job, UUID claimToken, String rawError) {
        int nextRetry = job.getRetryCount() + 1;
        Instant now = Instant.now();
        String sanitizedError = sanitizeError(rawError);

        if (nextRetry >= maxAttempts) {
            // Exhausted initial retries -> DEAD_LETTER
            notificationLogRepository.updateExecutionResult(
                    job.getId(),
                    claimToken,
                    NotificationStatus.DEAD_LETTER,
                    null,
                    sanitizedError,
                    nextRetry,
                    null,
                    null,
                    now
            );
            notificationMetrics.incrementDlq(job.getEventType());
            log.warn("Notification {} transitioned to DEAD_LETTER after {} attempts", job.getId(), nextRetry);
        } else {
            // Exponential backoff: initialBackoffMs * 2^(nextRetry - 1)
            long backoffMs = initialBackoffMs * (1L << (nextRetry - 1));
            Instant nextAttempt = now.plusMillis(backoffMs);

            notificationLogRepository.updateExecutionResult(
                    job.getId(),
                    claimToken,
                    NotificationStatus.FAILED,
                    null,
                    sanitizedError,
                    nextRetry,
                    nextAttempt,
                    null,
                    now
            );
            notificationMetrics.incrementFailed(job.getEventType());
            log.info("Notification {} failed attempt {}, next attempt at {}", job.getId(), nextRetry, nextAttempt);
        }
    }

    private String sanitizeError(String error) {
        if (error == null || error.isBlank()) {
            return "Unknown SMTP delivery error";
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }
}
