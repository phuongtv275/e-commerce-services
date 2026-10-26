package com.example.notificationservice.repository;

import com.example.notificationservice.domain.NotificationChannel;
import com.example.notificationservice.domain.NotificationLog;
import com.example.notificationservice.domain.NotificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationLogRepository extends JpaRepository<NotificationLog, UUID> {

    Optional<NotificationLog> findByEventIdAndChannel(String eventId, NotificationChannel channel);

    boolean existsByEventIdAndChannel(String eventId, NotificationChannel channel);

    Page<NotificationLog> findByStatus(NotificationStatus status, Pageable pageable);

    @Query("SELECT n FROM NotificationLog n WHERE n.status IN :statuses " +
            "AND (n.nextAttemptAt IS NULL OR n.nextAttemptAt <= :now) " +
            "AND n.retryCount < :maxRetries " +
            "AND (n.claimToken IS NULL OR n.updatedAt <= :leaseCutoff) " +
            "AND n.subject != '[PURGED_PII]' " +
            "ORDER BY n.queuedAt ASC")
    List<NotificationLog> findSendCandidates(
            @Param("statuses") Collection<NotificationStatus> statuses,
            @Param("now") Instant now,
            @Param("maxRetries") int maxRetries,
            @Param("leaseCutoff") Instant leaseCutoff,
            Pageable pageable
    );

    @Modifying
    @Transactional
    @Query("UPDATE NotificationLog n SET n.claimToken = :claimToken, n.updatedAt = :now " +
            "WHERE n.id = :id AND (n.claimToken IS NULL OR n.updatedAt <= :leaseCutoff) AND n.status IN :statuses")
    int claimNotification(
            @Param("id") UUID id,
            @Param("claimToken") UUID claimToken,
            @Param("leaseCutoff") Instant leaseCutoff,
            @Param("statuses") Collection<NotificationStatus> statuses,
            @Param("now") Instant now
    );

    @Modifying
    @Transactional
    @Query("UPDATE NotificationLog n SET n.status = :status, n.sentAt = :sentAt, n.claimToken = NULL, " +
            "n.updatedAt = :now, n.errorMessage = :errorMessage, n.retryCount = :retryCount, " +
            "n.nextAttemptAt = :nextAttemptAt, n.providerMessageId = :providerMessageId " +
            "WHERE n.id = :id AND n.claimToken = :claimToken")
    int updateExecutionResult(
            @Param("id") UUID id,
            @Param("claimToken") UUID claimToken,
            @Param("status") NotificationStatus status,
            @Param("sentAt") Instant sentAt,
            @Param("errorMessage") String errorMessage,
            @Param("retryCount") int retryCount,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("providerMessageId") String providerMessageId,
            @Param("now") Instant now
    );

    @Query("SELECT n FROM NotificationLog n WHERE " +
            "( (n.status = 'DEAD_LETTER' AND n.updatedAt <= :cooldownCutoff) " +
            "  OR (n.status = 'REDRIVING' AND n.updatedAt <= :leaseCutoff) ) " +
            "AND n.retryCount < :maxRetries " +
            "AND n.subject != '[PURGED_PII]' " +
            "ORDER BY n.updatedAt ASC")
    List<NotificationLog> findRedriveCandidates(
            @Param("cooldownCutoff") Instant cooldownCutoff,
            @Param("leaseCutoff") Instant leaseCutoff,
            @Param("maxRetries") int maxRetries,
            Pageable pageable
    );

    @Modifying
    @Transactional
    @Query("UPDATE NotificationLog n SET n.status = 'REDRIVING', n.claimToken = :claimToken, n.updatedAt = :now " +
            "WHERE n.id = :id AND n.subject != '[PURGED_PII]' AND n.retryCount < :maxRetries " +
            "AND ( (n.status = 'DEAD_LETTER' AND n.updatedAt <= :cooldownCutoff) OR (n.status = 'REDRIVING' AND n.updatedAt <= :leaseCutoff) )")
    int claimForRedrive(
            @Param("id") UUID id,
            @Param("claimToken") UUID claimToken,
            @Param("cooldownCutoff") Instant cooldownCutoff,
            @Param("leaseCutoff") Instant leaseCutoff,
            @Param("maxRetries") int maxRetries,
            @Param("now") Instant now
    );

    @Query("SELECT n FROM NotificationLog n WHERE " +
            "( (n.status IN ('SENT', 'DELIVERED') AND n.sentAt < :cutoff) " +
            "  OR (n.status NOT IN ('SENT', 'DELIVERED') AND (n.queuedAt < :cutoff OR n.updatedAt < :cutoff)) ) " +
            "AND n.subject != '[PURGED_PII]'")
    List<NotificationLog> findRetentionPurgeCandidates(@Param("cutoff") Instant cutoff, Pageable pageable);
}
