package com.ecommerce.common.idempotency;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
public class IdempotentClaimManager {

    private static final long CLAIM_TIMEOUT_SECONDS = 60L;

    private final ProcessedEventRepository repository;
    private final TransactionTemplate transactionTemplate;

    public IdempotentClaimManager(ProcessedEventRepository repository) {
        this(repository, null);
    }

    @Autowired
    public IdempotentClaimManager(ProcessedEventRepository repository,
                                  @Autowired(required = false) PlatformTransactionManager transactionManager) {
        this.repository = repository;
        if (transactionManager != null) {
            this.transactionTemplate = new TransactionTemplate(transactionManager);
            this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        } else {
            this.transactionTemplate = null;
        }
    }

    public boolean claim(String eventId, String consumerGroup) {
        if (transactionTemplate == null) {
            return directClaim(eventId, consumerGroup);
        }

        try {
            Boolean result = transactionTemplate.execute(status -> directClaim(eventId, consumerGroup));
            return Boolean.TRUE.equals(result);
        } catch (UnexpectedRollbackException | DataIntegrityViolationException e) {
            log.warn("Concurrent claim collision detected for event id={} in consumer group {}. Claim failed gracefully.",
                    eventId, consumerGroup);
            return false;
        }
    }

    private boolean directClaim(String eventId, String consumerGroup) {
        Optional<ProcessedEvent> existingOpt = repository.findByEventIdAndConsumerGroup(eventId, consumerGroup);
        if (existingOpt.isEmpty() && repository.existsByEventIdAndConsumerGroup(eventId, consumerGroup)) {
            return false;
        }
        Instant now = Instant.now();

        if (existingOpt.isPresent()) {
            ProcessedEvent existing = existingOpt.get();
            if (existing.getStatus() == ProcessedStatus.COMPLETED) {
                return false;
            }
            if (existing.getStatus() == ProcessedStatus.PROCESSING && existing.getExpiresAt() != null
                    && existing.getExpiresAt().isAfter(now)) {
                // Đang được xử lý bởi worker khác và chưa hết hạn lease
                return false;
            }
            // Worker trước bị crash hoặc hết hạn lease: gia hạn và chiếm lại quyền xử lý
            existing.setStatus(ProcessedStatus.PROCESSING);
            existing.setExpiresAt(now.plusSeconds(CLAIM_TIMEOUT_SECONDS));
            existing.setProcessedAt(now);
            repository.saveAndFlush(existing);
            return true;
        }

        try {
            ProcessedEvent record = ProcessedEvent.builder()
                    .id(UUID.randomUUID())
                    .eventId(eventId)
                    .consumerGroup(consumerGroup)
                    .status(ProcessedStatus.PROCESSING)
                    .expiresAt(now.plusSeconds(CLAIM_TIMEOUT_SECONDS))
                    .processedAt(now)
                    .build();
            repository.saveAndFlush(record);
            return true;
        } catch (DataIntegrityViolationException e) {
            log.warn("Concurrent duplicate execution detected for event id={} in consumer group {}. Claim failed.",
                    eventId, consumerGroup);
            return false;
        }
    }

    public void complete(String eventId, String consumerGroup) {
        if (transactionTemplate == null) {
            directComplete(eventId, consumerGroup);
            return;
        }
        try {
            transactionTemplate.execute(status -> {
                directComplete(eventId, consumerGroup);
                return null;
            });
        } catch (Exception e) {
            log.warn("Failed to mark event id={} as COMPLETED in group {}: {}", eventId, consumerGroup, e.getMessage());
        }
    }

    private void directComplete(String eventId, String consumerGroup) {
        repository.findByEventIdAndConsumerGroup(eventId, consumerGroup).ifPresent(record -> {
            record.setStatus(ProcessedStatus.COMPLETED);
            record.setExpiresAt(null);
            record.setProcessedAt(Instant.now());
            repository.saveAndFlush(record);
        });
    }

    public void release(String eventId, String consumerGroup) {
        if (transactionTemplate == null) {
            directRelease(eventId, consumerGroup);
            return;
        }
        try {
            transactionTemplate.execute(status -> {
                directRelease(eventId, consumerGroup);
                return null;
            });
        } catch (Exception e) {
            log.warn("Failed to release claim for event id={} in group {}: {}", eventId, consumerGroup, e.getMessage());
        }
    }

    private void directRelease(String eventId, String consumerGroup) {
        repository.findByEventIdAndConsumerGroup(eventId, consumerGroup).ifPresent(record -> {
            if (record.getStatus() == ProcessedStatus.PROCESSING) {
                repository.delete(record);
            }
        });
    }
}
