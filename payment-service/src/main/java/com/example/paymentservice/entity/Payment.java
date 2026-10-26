package com.example.paymentservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false, unique = true)
    private UUID orderId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 0)
    private BigDecimal amount;

    @Builder.Default
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "VND";

    @Builder.Default
    @Column(name = "provider", nullable = false, length = 50)
    private String provider = "VNPAY";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private PaymentStatus status;

    @Column(name = "provider_txn_ref", nullable = false, unique = true, length = 100)
    private String providerTxnRef;

    @Column(name = "provider_create_date", nullable = false, length = 14)
    private String providerCreateDate;

    @Column(name = "provider_transaction_no", length = 15)
    private String providerTransactionNo;

    @Column(name = "payment_url", columnDefinition = "TEXT")
    private String paymentUrl;

    @Builder.Default
    @Column(name = "total_refunded", nullable = false, precision = 19, scale = 0)
    private BigDecimal totalRefunded = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "refund_in_progress", nullable = false, precision = 19, scale = 0)
    private BigDecimal refundInProgress = BigDecimal.ZERO;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "reconciliation_claim_until")
    private Instant reconciliationClaimUntil;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (currency == null) {
            currency = "VND";
        }
        if (provider == null) {
            provider = "VNPAY";
        }
        if (totalRefunded == null) {
            totalRefunded = BigDecimal.ZERO;
        }
        if (refundInProgress == null) {
            refundInProgress = BigDecimal.ZERO;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
