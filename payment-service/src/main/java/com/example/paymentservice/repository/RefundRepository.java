package com.example.paymentservice.repository;

import com.example.paymentservice.entity.Refund;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    Optional<Refund> findByIdempotencyKey(String idempotencyKey);

    Optional<Refund> findByProviderRequestId(String providerRequestId);

    List<Refund> findByPaymentId(UUID paymentId);

    List<Refund> findByOrderId(UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Refund r WHERE r.id = :id")
    Optional<Refund> findByIdForUpdate(@Param("id") UUID id);

    @Query(value = "SELECT r.* FROM refunds r " +
            "WHERE r.status = :status AND r.created_at < :cutoff AND r.retry_count < :maxRetries " +
            "AND (r.reconciliation_claim_until IS NULL OR r.reconciliation_claim_until <= :now) " +
            "ORDER BY r.created_at, r.id LIMIT :batchSize FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<Refund> claimRefundsForReconciliation(
            @Param("status") String status,
            @Param("cutoff") Instant cutoff,
            @Param("maxRetries") int maxRetries,
            @Param("now") Instant now,
            @Param("batchSize") int batchSize
    );
}
