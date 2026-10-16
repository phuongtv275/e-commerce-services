package com.ecommerce.common.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    boolean existsByEventIdAndConsumerGroup(String eventId, String consumerGroup);

    Optional<ProcessedEvent> findByEventIdAndConsumerGroup(String eventId, String consumerGroup);
}
