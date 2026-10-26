package com.example.notificationservice.service;

import com.example.notificationservice.domain.NotificationLog;
import com.example.notificationservice.domain.NotificationStatus;
import com.example.notificationservice.repository.NotificationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RetentionPurgeJob {

    private final NotificationLogRepository notificationLogRepository;

    @Value("${notification.retention.days:30}")
    private long retentionDays;

    @Scheduled(
            cron = "${notification.retention.purge-cron:0 0 3 * * *}",
            zone = "${notification.retention.zone:UTC}"
    )
    public void executePurge() {
        log.info("Starting daily PII retention purge job for records older than {} days", retentionDays);
        int purgedCount = purgeOldRecords();
        log.info("PII retention purge job finished. Purged {} records", purgedCount);
    }

    @Transactional
    public int purgeOldRecords() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        int totalPurged = 0;
        int batchSize = 100;

        while (true) {
            List<NotificationLog> candidates = notificationLogRepository.findRetentionPurgeCandidates(
                    cutoff,
                    PageRequest.of(0, batchSize)
            );

            if (candidates.isEmpty()) {
                break;
            }

            for (NotificationLog logEntry : candidates) {
                logEntry.setSubject("[PURGED_PII]");
                logEntry.setRenderedBody("[PURGED_PII]");
                logEntry.setBodyPreview("[PURGED_PII]");
                logEntry.setPayload(null);
                logEntry.setErrorMessage("[PURGED_PII_LOG]");
                logEntry.setRecipient(RecipientMasker.mask(logEntry.getRecipient()));

                if (logEntry.getStatus() == NotificationStatus.QUEUED
                        || logEntry.getStatus() == NotificationStatus.REDRIVING) {
                    logEntry.setStatus(NotificationStatus.EXPIRED_UNSENT);
                }

                logEntry.setUpdatedAt(Instant.now());
                notificationLogRepository.save(logEntry);
                totalPurged++;
            }
        }

        return totalPurged;
    }
}
