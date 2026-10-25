package com.example.orderservice.repository;

import com.example.orderservice.entity.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

    Optional<IdempotencyRecord> findByCustomerIdAndOperationAndIdempotencyKey(
            UUID customerId, String operation, String idempotencyKey);

    @Modifying
    @Query(value = """
        INSERT INTO idempotency_records (id, customer_id, operation, idempotency_key, request_hash, status, attempt_order_id, expires_at, created_at, updated_at)
        VALUES (:id, :customerId, :operation, :idempotencyKey, :requestHash, 'IN_PROGRESS', :attemptOrderId, :expiresAt, :now, :now)
        ON CONFLICT (customer_id, operation, idempotency_key) DO NOTHING
        """, nativeQuery = true)
    int insertIfNotExists(
            @Param("id") UUID id,
            @Param("customerId") UUID customerId,
            @Param("operation") String operation,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("attemptOrderId") UUID attemptOrderId,
            @Param("expiresAt") Instant expiresAt,
            @Param("now") Instant now
    );

    @Modifying
    @Query("UPDATE IdempotencyRecord r SET r.status = com.example.orderservice.entity.IdempotencyStatus.IN_PROGRESS, r.attemptOrderId = :newAttemptOrderId, r.expiresAt = :newExpiresAt, r.updatedAt = :now WHERE r.id = :id AND r.status = com.example.orderservice.entity.IdempotencyStatus.FAILED")
    int reclaimFailedRecord(
            @Param("id") UUID id,
            @Param("newAttemptOrderId") UUID newAttemptOrderId,
            @Param("newExpiresAt") Instant newExpiresAt,
            @Param("now") Instant now
    );
}
