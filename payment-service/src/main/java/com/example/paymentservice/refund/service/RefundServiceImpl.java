package com.example.paymentservice.refund.service;

import com.ecommerce.common.event.RefundCompletedEvent;
import com.ecommerce.common.exception.ConflictException;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.ecommerce.common.outbox.OutboxEvent;
import com.ecommerce.common.outbox.OutboxEventRepository;
import com.ecommerce.common.outbox.OutboxStatus;
import com.example.paymentservice.entity.*;
import com.example.paymentservice.provider.vnpay.VnPayQueryRequest;
import com.example.paymentservice.provider.vnpay.VnPayQueryResponse;
import com.example.paymentservice.provider.vnpay.VnPayRefundRequest;
import com.example.paymentservice.provider.vnpay.VnPayRefundResponse;
import com.example.paymentservice.provider.vnpay.VnpayClient;
import com.example.paymentservice.refund.dto.RefundRequest;
import com.example.paymentservice.refund.dto.RefundResolution;
import com.example.paymentservice.refund.dto.RefundResponse;
import com.example.paymentservice.refund.dto.ResolveRefundRequest;
import com.example.paymentservice.repository.PaymentAuditLogRepository;
import com.example.paymentservice.repository.PaymentRepository;
import com.example.paymentservice.repository.RefundRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class RefundServiceImpl implements RefundService {

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final PaymentAuditLogRepository auditLogRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final VnpayClient vnpayClient;
    private final com.example.paymentservice.config.VnPayProperties vnPayProperties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    private static final DateTimeFormatter VNP_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private String resolveEgressIp() {
        if (vnPayProperties != null && vnPayProperties.getIpAddress() != null && !vnPayProperties.getIpAddress().isBlank()) {
            return vnPayProperties.getIpAddress();
        }
        throw new IllegalStateException("vnpay.ip-address must be configured with a valid literal egress IP address");
    }

    public RefundServiceImpl(PaymentRepository paymentRepository,
                             RefundRepository refundRepository,
                             PaymentAuditLogRepository auditLogRepository,
                             OutboxEventRepository outboxEventRepository,
                             VnpayClient vnpayClient,
                             com.example.paymentservice.config.VnPayProperties vnPayProperties,
                             ObjectMapper objectMapper,
                             PlatformTransactionManager transactionManager) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.auditLogRepository = auditLogRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.vnpayClient = vnpayClient;
        this.vnPayProperties = vnPayProperties;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public RefundResponse createManualRefund(UUID paymentId, RefundRequest request, UUID actorId) {
        log.info("Initiating manual refund for paymentId={}, amount={}, key={}", paymentId, request.getAmount(), request.getIdempotencyKey());

        // Step 1: Claim and hold in DB transaction
        ClaimResult claimResult;
        try {
            claimResult = transactionTemplate.execute(status -> claimManualRefundTx(paymentId, request, actorId));
        } catch (DataIntegrityViolationException ex) {
            Refund existing = refundRepository.findByIdempotencyKey(request.getIdempotencyKey()).orElseThrow(() -> ex);
            if (!existing.getPaymentId().equals(paymentId)) {
                throw new ConflictException("Idempotency key " + request.getIdempotencyKey() + " belongs to different payment");
            }
            return RefundResponse.fromEntity(existing);
        }

        if (claimResult == null || claimResult.refund == null) {
            throw new IllegalStateException("Failed to claim refund");
        }

        Refund refund = claimResult.refund;

        // If idempotent replay of an already processed or in-progress refund, return existing record
        if (claimResult.alreadyExists) {
            log.info("Idempotent replay detected for refund key={}", request.getIdempotencyKey());
            return RefundResponse.fromEntity(refund);
        }

        // Step 2: Invoke VNPAY outside database transaction using server egress IP
        invokeVnPayRefundAndApplyResult(claimResult.payment, refund, actorId != null ? actorId.toString() : "FINANCE_STAFF");

        // Reload latest refund state
        Refund updatedRefund = refundRepository.findById(refund.getId()).orElse(refund);
        return RefundResponse.fromEntity(updatedRefund);
    }

    @Override
    public RefundResponse resolveRefund(UUID paymentId, UUID refundId, ResolveRefundRequest request, String actor) {
        log.info("Resolving refund refundId={} for paymentId={} with resolution={}", refundId, paymentId, request.getResolution());

        return transactionTemplate.execute(status -> {
            Refund refund = refundRepository.findByIdForUpdate(refundId)
                    .orElseThrow(() -> new ResourceNotFoundException("Refund not found: " + refundId));

            if (!refund.getPaymentId().equals(paymentId)) {
                throw new ConflictException("Refund " + refundId + " does not belong to payment " + paymentId);
            }

            // Check if already in terminal state
            if (refund.getStatus() == RefundStatus.SUCCEEDED) {
                if (request.getResolution() == RefundResolution.CONFIRM_SUCCEEDED) {
                    return RefundResponse.fromEntity(refund);
                }
                throw new ConflictException("Refund " + refundId + " is already SUCCEEDED");
            }
            if (refund.getStatus() == RefundStatus.FAILED) {
                if (request.getResolution() == RefundResolution.CONFIRM_FAILED) {
                    return RefundResponse.fromEntity(refund);
                }
                throw new ConflictException("Refund " + refundId + " is already FAILED");
            }

            if (refund.getStatus() != RefundStatus.MANUAL_RECONCILIATION_REQUIRED) {
                throw new ConflictException("Refund " + refundId + " cannot be resolved from status " + refund.getStatus());
            }

            Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                    .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + paymentId));

            Instant now = Instant.now();
            refund.setProcessedAt(now);

            if (request.getResolution() == RefundResolution.CONFIRM_SUCCEEDED) {
                refund.setStatus(RefundStatus.SUCCEEDED);
                if (request.getProviderRefundId() != null && !request.getProviderRefundId().isBlank()) {
                    refund.setProviderTransactionNo(request.getProviderRefundId());
                }

                // Balance update
                payment.setRefundInProgress(payment.getRefundInProgress().subtract(refund.getAmount()));
                payment.setTotalRefunded(payment.getTotalRefunded().add(refund.getAmount()));

                validatePaymentCeiling(payment);
                recalculatePaymentStatus(payment);

                refundRepository.save(refund);
                paymentRepository.save(payment);

                recordRefundCompletedOutbox(refund, payment, UUID.randomUUID().toString());
                recordAudit(payment.getId(), refund.getId(), "REFUND_RESOLVED_SUCCEEDED",
                        RefundStatus.MANUAL_RECONCILIATION_REQUIRED.name(), RefundStatus.SUCCEEDED.name(),
                        actor, request.getNote());

            } else {
                refund.setStatus(RefundStatus.FAILED);

                // Release hold
                payment.setRefundInProgress(payment.getRefundInProgress().subtract(refund.getAmount()));

                validatePaymentCeiling(payment);
                recalculatePaymentStatus(payment);

                refundRepository.save(refund);
                paymentRepository.save(payment);

                recordAudit(payment.getId(), refund.getId(), "REFUND_RESOLVED_FAILED",
                        RefundStatus.MANUAL_RECONCILIATION_REQUIRED.name(), RefundStatus.FAILED.name(),
                        actor, request.getNote());
            }

            return RefundResponse.fromEntity(refund);
        });
    }

    @Override
    public Refund processAutoRefund(UUID orderId, BigDecimal amount, String reason, String correlationId) {
        log.info("Processing auto-refund for orderId={}, requestedAmount={}", orderId, amount);

        String idempotencyKey = "AUTO_REFUND:" + orderId;

        // Step 1: Claim in DB transaction
        ClaimResult claimResult;
        try {
            claimResult = transactionTemplate.execute(status -> {
                Optional<Refund> existing = refundRepository.findByIdempotencyKey(idempotencyKey);
                if (existing.isPresent()) {
                    log.info("Auto refund for orderId={} already claimed with key={}", orderId, idempotencyKey);
                    return new ClaimResult(null, existing.get(), true);
                }

            Optional<Payment> paymentOpt = paymentRepository.findByOrderIdForUpdate(orderId);
            if (paymentOpt.isEmpty()) {
                log.warn("Auto-refund skipped: payment not found for orderId={}", orderId);
                return null;
            }

            Payment payment = paymentOpt.get();

            // Auto-refund only applicable if funds collected
            if (payment.getStatus() != PaymentStatus.SUCCEEDED &&
                    payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED &&
                    payment.getStatus() != PaymentStatus.REFUND_PENDING) {
                log.info("Auto-refund skipped: payment status is {}, no collected funds", payment.getStatus());
                recordAudit(payment.getId(), null, "AUTO_REFUND_SKIPPED_NOT_COLLECTED",
                        payment.getStatus().name(), payment.getStatus().name(), "SYSTEM", "Status=" + payment.getStatus());
                return null;
            }

            BigDecimal available = payment.getAmount()
                    .subtract(payment.getTotalRefunded())
                    .subtract(payment.getRefundInProgress());

            if (available.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("Auto-refund skipped: no refundable balance available for orderId={}", orderId);
                return null;
            }

            // Refund remaining amount
            BigDecimal refundAmount = available;

            String providerRequestId = UUID.randomUUID().toString().replace("-", "");

            Refund refund = Refund.builder()
                    .paymentId(payment.getId())
                    .orderId(orderId)
                    .amount(refundAmount)
                    .reason(reason != null ? reason : "Automated refund compensation")
                    .status(RefundStatus.PENDING)
                    .idempotencyKey(idempotencyKey)
                    .providerRequestId(providerRequestId)
                    .retryCount(0)
                    .build();

            refund = refundRepository.save(refund);

            payment.setRefundInProgress(payment.getRefundInProgress().add(refundAmount));
            payment.setStatus(PaymentStatus.REFUND_PENDING);
            paymentRepository.save(payment);

            recordAudit(payment.getId(), refund.getId(), "AUTO_REFUND_CLAIMED",
                    PaymentStatus.SUCCEEDED.name(), PaymentStatus.REFUND_PENDING.name(), "SYSTEM", reason);

                return new ClaimResult(payment, refund, false);
            });
        } catch (DataIntegrityViolationException ex) {
            return refundRepository.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> ex);
        }

        if (claimResult == null || claimResult.refund == null || claimResult.alreadyExists) {
            return claimResult != null ? claimResult.refund : null;
        }

        // Step 2: Invoke VNPAY outside transaction
        invokeVnPayRefundAndApplyResult(claimResult.payment, claimResult.refund, "SYSTEM");

        return refundRepository.findById(claimResult.refund.getId()).orElse(claimResult.refund);
    }

    @Override
    public void reconcilePendingRefund(UUID refundId) {
        log.info("Reconciling pending refund refundId={}", refundId);

        // Pre-check and retry increment in TX
        Payment payment = transactionTemplate.execute(status -> {
            Optional<Refund> refundOpt = refundRepository.findByIdForUpdate(refundId);
            if (refundOpt.isEmpty()) {
                return null;
            }
            Refund refund = refundOpt.get();
            if (refund.getStatus() != RefundStatus.PENDING) {
                return null;
            }

            Payment p = paymentRepository.findByIdForUpdate(refund.getPaymentId())
                    .orElseThrow(() -> new IllegalStateException("Payment not found"));

            if (refund.getRetryCount() >= 3) {
                log.warn("Refund {} exceeded max retries. Moving to MANUAL_RECONCILIATION_REQUIRED", refundId);
                refund.setStatus(RefundStatus.MANUAL_RECONCILIATION_REQUIRED);
                p.setStatus(PaymentStatus.MANUAL_RECONCILIATION_REQUIRED);
                refundRepository.save(refund);
                paymentRepository.save(p);

                recordAudit(p.getId(), refund.getId(), "REFUND_MOVED_TO_MANUAL",
                        RefundStatus.PENDING.name(), RefundStatus.MANUAL_RECONCILIATION_REQUIRED.name(),
                        "SCHEDULER", "Max retries exceeded");
                return null;
            }

            refund.setRetryCount(refund.getRetryCount() + 1);
            refundRepository.save(refund);
            return p;
        });

        if (payment == null) {
            return;
        }

        // Query VNPAY outside transaction using querydr
        String queryRequestId = UUID.randomUUID().toString().replace("-", "");

        VnPayQueryRequest queryRequest = VnPayQueryRequest.builder()
                .requestId(queryRequestId)
                .txnRef(payment.getProviderTxnRef())
                .transactionDate(payment.getProviderCreateDate())
                .createDate(VNP_DATE_FORMATTER.format(Instant.now()))
                .orderInfo("Reconciliation query for payment " + payment.getOrderId())
                .ipAddress(resolveEgressIp())
                .build();

        VnPayQueryResponse queryResponse = null;
        try {
            queryResponse = vnpayClient.queryTransaction(queryRequest);
        } catch (Exception e) {
            log.error("Error querying transaction for refundId={}: {}", refundId, e.getMessage());
        }

        final VnPayQueryResponse finalQueryResponse = queryResponse;

        // Apply query result in DB TX: querydr describes the original payment and cannot establish
        // that a specific refund succeeded. Keep hold/pending and move to MANUAL_RECONCILIATION_REQUIRED after 3 indeterminate attempts.
        transactionTemplate.executeWithoutResult(status -> {
            Refund refund = refundRepository.findByIdForUpdate(refundId).orElse(null);
            if (refund == null || refund.getStatus() != RefundStatus.PENDING) {
                return;
            }
            Payment p = paymentRepository.findByIdForUpdate(refund.getPaymentId()).orElse(null);
            if (p == null) {
                return;
            }

            recordAudit(p.getId(), refund.getId(), "REFUND_RECONCILIATION_QUERYDR_INDETERMINATE",
                    RefundStatus.PENDING.name(), RefundStatus.PENDING.name(),
                    "SCHEDULER", finalQueryResponse != null
                            ? "VNPAY querydr responseCode=" + finalQueryResponse.getResponseCode() + ", txStatus=" + finalQueryResponse.getTransactionStatus()
                            : "Querydr returned null or timeout");

            // Indeterminate result: if retries reached 3, move to manual reconciliation
            if (refund.getRetryCount() >= 3) {
                refund.setStatus(RefundStatus.MANUAL_RECONCILIATION_REQUIRED);
                p.setStatus(PaymentStatus.MANUAL_RECONCILIATION_REQUIRED);
                refundRepository.save(refund);
                paymentRepository.save(p);

                recordAudit(p.getId(), refund.getId(), "REFUND_MOVED_TO_MANUAL",
                        RefundStatus.PENDING.name(), RefundStatus.MANUAL_RECONCILIATION_REQUIRED.name(),
                        "SCHEDULER", "Indeterminate query result after 3 attempts");
            }
        });
    }

    private ClaimResult claimManualRefundTx(UUID paymentId, RefundRequest request, UUID actorId) {
        // Resolve idempotency key first
        Optional<Refund> existing = refundRepository.findByIdempotencyKey(request.getIdempotencyKey());
        if (existing.isPresent()) {
            Refund r = existing.get();
            if (!r.getPaymentId().equals(paymentId)) {
                throw new ConflictException("Idempotency key " + request.getIdempotencyKey() + " belongs to different payment");
            }
            return new ClaimResult(null, r, true);
        }

        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + paymentId));

        existing = refundRepository.findByIdempotencyKey(request.getIdempotencyKey());
        if (existing.isPresent()) {
            Refund r = existing.get();
            if (!r.getPaymentId().equals(paymentId)) {
                throw new ConflictException("Idempotency key " + request.getIdempotencyKey() + " belongs to different payment");
            }
            return new ClaimResult(null, r, true);
        }

        if (payment.getStatus() == PaymentStatus.MANUAL_RECONCILIATION_REQUIRED) {
            throw new ConflictException("Payment is in MANUAL_RECONCILIATION_REQUIRED; new refunds prohibited");
        }

        if (payment.getStatus() != PaymentStatus.SUCCEEDED && payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED) {
            throw new ConflictException("Payment status " + payment.getStatus() + " does not permit refunds");
        }

        // Check if any refund is in manual reconciliation
        List<Refund> existingRefunds = refundRepository.findByPaymentId(paymentId);
        boolean hasManual = existingRefunds.stream()
                .anyMatch(r -> r.getStatus() == RefundStatus.MANUAL_RECONCILIATION_REQUIRED);
        if (hasManual) {
            throw new ConflictException("Payment has unresolved refunds in MANUAL_RECONCILIATION_REQUIRED");
        }

        BigDecimal available = payment.getAmount()
                .subtract(payment.getTotalRefunded())
                .subtract(payment.getRefundInProgress());

        if (request.getAmount().compareTo(available) > 0) {
            throw new ConflictException("Refund amount " + request.getAmount() + " exceeds available ceiling " + available);
        }

        String providerRequestId = UUID.randomUUID().toString().replace("-", "");

        Refund refund = Refund.builder()
                .paymentId(paymentId)
                .orderId(payment.getOrderId())
                .amount(request.getAmount())
                .reason(request.getReason())
                .status(RefundStatus.PENDING)
                .requestedBy(actorId)
                .providerRequestId(providerRequestId)
                .idempotencyKey(request.getIdempotencyKey())
                .retryCount(0)
                .build();

        refund = refundRepository.save(refund);

        payment.setRefundInProgress(payment.getRefundInProgress().add(request.getAmount()));
        payment.setStatus(PaymentStatus.REFUND_PENDING);
        paymentRepository.save(payment);

        recordAudit(payment.getId(), refund.getId(), "REFUND_CLAIMED",
                PaymentStatus.SUCCEEDED.name(), PaymentStatus.REFUND_PENDING.name(),
                actorId != null ? actorId.toString() : "FINANCE_STAFF", request.getReason());

        return new ClaimResult(payment, refund, false);
    }

    private void invokeVnPayRefundAndApplyResult(Payment payment, Refund refund, String actor) {
        // Determine transaction type: 02 = full refund, 03 = partial refund
        boolean isFull = refund.getAmount().compareTo(payment.getAmount()) == 0
                && payment.getTotalRefunded().compareTo(BigDecimal.ZERO) == 0;
        String transactionType = isFull ? "02" : "03";

        VnPayRefundRequest vnpayReq = VnPayRefundRequest.builder()
                .requestId(refund.getProviderRequestId())
                .txnRef(payment.getProviderTxnRef())
                .amount(refund.getAmount())
                .transactionType(transactionType)
                .transactionNo(payment.getProviderTransactionNo())
                .transactionDate(payment.getProviderCreateDate())
                .createBy(actor)
                .ipAddress(resolveEgressIp())
                .orderInfo(refund.getReason())
                .build();

        VnPayRefundResponse vnpayResp = null;
        try {
            vnpayResp = vnpayClient.refund(vnpayReq);
        } catch (Exception e) {
            log.error("Network or provider exception during VNPAY refund invocation: {}", e.getMessage());
        }

        final VnPayRefundResponse finalResp = vnpayResp;

        // Apply result in separate DB transaction
        transactionTemplate.executeWithoutResult(status -> {
            Refund r = refundRepository.findByIdForUpdate(refund.getId()).orElse(refund);
            Payment p = paymentRepository.findByIdForUpdate(payment.getId()).orElse(payment);

            if (finalResp != null) {
                String respCode = finalResp.getResponseCode();
                String txStatus = finalResp.getTransactionStatus();

                if ("00".equals(respCode) && "00".equals(txStatus)) {
                    // Definitive success ONLY when both responseCode=="00" AND transactionStatus=="00"
                    applyDefiniteSuccess(r, p, finalResp.getTransactionNo(), actor);
                } else if ("05".equals(txStatus) || "06".equals(txStatus) || "94".equals(respCode) || ("00".equals(respCode) && txStatus == null)) {
                    // In-progress, duplicate, or accepted without final status (txStatus == null): retain hold!
                    log.info("VNPAY refund pending/in-progress or accepted without terminal status. Retaining hold for refundId={}", r.getId());
                    recordAudit(p.getId(), r.getId(), "REFUND_VNPAY_PENDING",
                            RefundStatus.PENDING.name(), RefundStatus.PENDING.name(), actor,
                            "ResponseCode=" + respCode + ", TransactionStatus=" + txStatus);
                } else {
                    // Definitive failure
                    log.warn("VNPAY refund rejected/failed with respCode={}, txStatus={}", respCode, txStatus);
                    applyDefiniteFailure(r, p, actor);
                }
            } else {
                // Ambiguous result (timeout, network exception): RETAIN HOLD
                log.warn("VNPAY refund invocation indeterminate. Retaining hold for reconciliation.");
                recordAudit(p.getId(), r.getId(), "REFUND_INVOCATION_INDETERMINATE",
                        RefundStatus.PENDING.name(), RefundStatus.PENDING.name(), actor, "Timeout or error");
            }
        });
    }

    private void applyDefiniteSuccess(Refund refund, Payment payment, String providerTxnNo, String actor) {
        refund.setStatus(RefundStatus.SUCCEEDED);
        refund.setProviderTransactionNo(providerTxnNo);
        refund.setProcessedAt(Instant.now());

        payment.setRefundInProgress(payment.getRefundInProgress().subtract(refund.getAmount()));
        payment.setTotalRefunded(payment.getTotalRefunded().add(refund.getAmount()));

        validatePaymentCeiling(payment);
        recalculatePaymentStatus(payment);

        refundRepository.save(refund);
        paymentRepository.save(payment);

        recordRefundCompletedOutbox(refund, payment, UUID.randomUUID().toString());
        recordAudit(payment.getId(), refund.getId(), "REFUND_SUCCEEDED",
                RefundStatus.PENDING.name(), RefundStatus.SUCCEEDED.name(), actor, "ProviderTxnNo=" + providerTxnNo);
    }

    private void applyDefiniteFailure(Refund refund, Payment payment, String actor) {
        refund.setStatus(RefundStatus.FAILED);
        refund.setProcessedAt(Instant.now());

        payment.setRefundInProgress(payment.getRefundInProgress().subtract(refund.getAmount()));

        validatePaymentCeiling(payment);
        recalculatePaymentStatus(payment);

        refundRepository.save(refund);
        paymentRepository.save(payment);

        recordAudit(payment.getId(), refund.getId(), "REFUND_FAILED",
                RefundStatus.PENDING.name(), RefundStatus.FAILED.name(), actor, "Provider rejection");
    }

    private void recalculatePaymentStatus(Payment payment) {
        List<Refund> refunds = refundRepository.findByPaymentId(payment.getId());

        boolean hasManual = refunds.stream()
                .anyMatch(r -> r.getStatus() == RefundStatus.MANUAL_RECONCILIATION_REQUIRED);
        boolean hasPending = refunds.stream()
                .anyMatch(r -> r.getStatus() == RefundStatus.PENDING);

        if (hasManual) {
            payment.setStatus(PaymentStatus.MANUAL_RECONCILIATION_REQUIRED);
        } else if (hasPending) {
            payment.setStatus(PaymentStatus.REFUND_PENDING);
        } else if (payment.getTotalRefunded().compareTo(payment.getAmount()) >= 0) {
            payment.setStatus(PaymentStatus.REFUNDED);
        } else if (payment.getTotalRefunded().compareTo(BigDecimal.ZERO) > 0) {
            payment.setStatus(PaymentStatus.PARTIALLY_REFUNDED);
        } else {
            payment.setStatus(PaymentStatus.SUCCEEDED);
        }
    }

    private void validatePaymentCeiling(Payment payment) {
        if (payment.getRefundInProgress().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalStateException("refundInProgress cannot be negative: " + payment.getRefundInProgress());
        }
        if (payment.getTotalRefunded().compareTo(payment.getAmount()) > 0) {
            throw new IllegalStateException("totalRefunded cannot exceed total amount");
        }
    }

    private void recordRefundCompletedOutbox(Refund refund, Payment payment, String correlationId) {
        RefundCompletedEvent event = RefundCompletedEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType("payment.refund-completed")
                .eventVersion(1)
                .occurredAt(Instant.now())
                .correlationId(correlationId)
                .producer("payment-service")
                .refundId(refund.getId())
                .paymentId(payment.getId())
                .orderId(payment.getOrderId())
                .amount(refund.getAmount())
                .currency(payment.getCurrency())
                .providerRefundId(refund.getProviderTransactionNo())
                .build();

        try {
            String payloadStr = objectMapper.writeValueAsString(event);
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .id(UUID.randomUUID())
                    .aggregateType("PAYMENT")
                    .aggregateId(payment.getOrderId().toString())
                    .eventType("payment.refund-completed")
                    .eventVersion(1)
                    .payload(payloadStr)
                    .status(OutboxStatus.PENDING)
                    .retryCount(0)
                    .createdAt(Instant.now())
                    .correlationId(correlationId)
                    .build();

            outboxEventRepository.save(outboxEvent);
            log.info("Saved outbox event payment.refund-completed for paymentId={}", payment.getId());
        } catch (Exception e) {
            log.error("Failed to serialize RefundCompletedEvent: {}", e.getMessage());
            throw new RuntimeException("Could not serialize outbox event", e);
        }
    }

    private void recordAudit(UUID paymentId, UUID refundId, String action, String oldStatus, String newStatus, String actor, String rawPayload) {
        String payloadJson = null;
        if (rawPayload != null) {
            try {
                payloadJson = objectMapper.writeValueAsString(rawPayload);
            } catch (Exception e) {
                payloadJson = "{\"raw\":\"" + rawPayload.replace("\"", "\\\"") + "\"}";
            }
        }
        PaymentAuditLog auditLog = PaymentAuditLog.builder()
                .paymentId(paymentId)
                .refundId(refundId)
                .action(action)
                .oldStatus(oldStatus)
                .newStatus(newStatus)
                .actor(actor != null ? actor : "UNKNOWN")
                .rawPayload(payloadJson)
                .occurredAt(Instant.now())
                .build();
        auditLogRepository.save(auditLog);
    }

    private record ClaimResult(Payment payment, Refund refund, boolean alreadyExists) {}
}
