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
import java.util.UUID;

@Slf4j
@Service
public class DlqRedriveWorker {

    private final NotificationLogRepository notificationLogRepository;
    private final JavaMailSender mailSender;
    private final NotificationMetrics notificationMetrics;

    @Value("${notification.mail.from:${MAIL_FROM:noreply@ecom.local}}")
    private String mailFrom;

    @Value("${notification.redrive.cooldown-minutes:5}")
    private long cooldownMinutes;

    @Value("${notification.worker.claim-lease-minutes:10}")
    private long leaseExpiryMinutes;

    @Value("${notification.redrive.batch-size:50}")
    private int batchSize;

    @Value("${notification.redrive.max-total-attempts:10}")
    private int maxTotalAttempts;

    public DlqRedriveWorker(
            NotificationLogRepository notificationLogRepository,
            JavaMailSender mailSender,
            NotificationMetrics notificationMetrics
    ) {
        this.notificationLogRepository = notificationLogRepository;
        this.mailSender = mailSender;
        this.notificationMetrics = notificationMetrics;
    }

    @Scheduled(cron = "${notification.redrive.cron:0 */15 * * * *}")
    public void scheduleRedrive() {
        log.info("Starting scheduled DLQ redrive run");
        redriveBatch(batchSize);
    }

    public int redriveBatch(int limit) {
        Instant now = Instant.now();
        Instant cooldownCutoff = now.minus(Duration.ofMinutes(cooldownMinutes));
        Instant leaseCutoff = now.minus(Duration.ofMinutes(leaseExpiryMinutes));

        List<NotificationLog> candidates = notificationLogRepository.findRedriveCandidates(
                cooldownCutoff,
                leaseCutoff,
                maxTotalAttempts,
                PageRequest.of(0, limit)
        );

        if (candidates.isEmpty()) {
            return 0;
        }

        int processed = 0;
        for (NotificationLog candidate : candidates) {
            if (redriveSingle(candidate.getId())) {
                processed++;
            }
        }
        return processed;
    }

    public boolean redriveSingle(UUID id) {
        UUID claimToken = UUID.randomUUID();
        Instant now = Instant.now();
        Instant cooldownCutoff = now.minus(Duration.ofMinutes(cooldownMinutes));
        Instant leaseCutoff = now.minus(Duration.ofMinutes(leaseExpiryMinutes));

        // 1. Atomic claim for redrive
        int claimed = claimRecordForRedrive(id, claimToken, cooldownCutoff, leaseCutoff, now);
        if (claimed == 0) {
            return false;
        }

        NotificationLog job = notificationLogRepository.findById(id).orElse(null);
        if (job == null) {
            return false;
        }

        notificationMetrics.incrementRedrive(job.getEventType());

        // 2. Send via SMTP outside DB transaction
        try {
            sendEmail(job.getRecipient(), job.getSubject(), job.getRenderedBody());
            updateSuccess(job.getId(), claimToken);
            notificationMetrics.incrementSent(job.getEventType());
            log.info("Successfully redrived email for notification {}", job.getId());
            return true;
        } catch (Exception e) {
            log.error("Failed redrive for notification {}: {}", job.getId(), e.getMessage());
            handleFailure(job, claimToken, e.getMessage());
            return false;
        }
    }

    @Transactional
    public int claimRecordForRedrive(UUID id, UUID claimToken, Instant cooldownCutoff, Instant leaseCutoff, Instant now) {
        return notificationLogRepository.claimForRedrive(
                id,
                claimToken,
                cooldownCutoff,
                leaseCutoff,
                maxTotalAttempts,
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
        String sanitizedError = (rawError != null && rawError.length() > 500)
                ? rawError.substring(0, 500)
                : rawError;

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
    }
}
