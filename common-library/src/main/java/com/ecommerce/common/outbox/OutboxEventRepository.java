package com.ecommerce.common.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query("SELECT e FROM OutboxEvent e WHERE e.status IN :statuses " +
           "AND (e.nextRetryAt IS NULL OR e.nextRetryAt <= :now) " +
           "ORDER BY e.createdAt ASC")
    List<OutboxEvent> findPendingEventsForPublishing(
            @Param("statuses") Collection<OutboxStatus> statuses,
            @Param("now") Instant now,
            Pageable pageable
    );

    List<OutboxEvent> findByStatus(OutboxStatus status);
}
