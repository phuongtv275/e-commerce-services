package com.ecommerce.common.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    boolean existsByEventIdAndConsumerGroup(String eventId, String consumerGroup);

    Optional<ProcessedEvent> findByEventIdAndConsumerGroup(String eventId, String consumerGroup);

    @Modifying
    @Query("UPDATE ProcessedEvent p SET p.status = :newStatus, p.expiresAt = :newExpiresAt, p.processedAt = :now " +
            "WHERE p.eventId = :eventId AND p.consumerGroup = :consumerGroup AND p.status = 'PROCESSING' AND p.expiresAt <= :now")
    int reclaimExpiredLease(@Param("eventId") String eventId,
                            @Param("consumerGroup") String consumerGroup,
                            @Param("newStatus") ProcessedStatus newStatus,
                            @Param("newExpiresAt") Instant newExpiresAt,
                            @Param("now") Instant now);
}
