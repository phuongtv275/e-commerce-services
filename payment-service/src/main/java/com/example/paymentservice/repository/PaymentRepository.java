package com.example.paymentservice.repository;

import com.example.paymentservice.entity.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByOrderId(UUID orderId);

    Optional<Payment> findByProviderTxnRef(String providerTxnRef);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.orderId = :orderId")
    Optional<Payment> findByOrderIdForUpdate(@Param("orderId") UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.providerTxnRef = :providerTxnRef")
    Optional<Payment> findByProviderTxnRefForUpdate(@Param("providerTxnRef") String providerTxnRef);

    @Query(value = "SELECT p.* FROM payments p " +
            "WHERE p.status = :status AND p.created_at < :cutoff " +
            "AND (p.reconciliation_claim_until IS NULL OR p.reconciliation_claim_until <= :now) " +
            "ORDER BY p.created_at, p.id LIMIT :batchSize FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<Payment> claimPaymentsForReconciliation(
            @Param("status") String status,
            @Param("cutoff") Instant cutoff,
            @Param("now") Instant now,
            @Param("batchSize") int batchSize
    );
}
