package com.example.paymentservice.scheduler;

import com.ecommerce.common.event.PaymentFailedEvent;
import com.ecommerce.common.event.PaymentSucceededEvent;
import com.ecommerce.common.outbox.OutboxEvent;
import com.ecommerce.common.outbox.OutboxEventRepository;
import com.ecommerce.common.outbox.OutboxStatus;
import com.example.paymentservice.entity.Payment;
import com.example.paymentservice.entity.PaymentAuditLog;
import com.example.paymentservice.entity.PaymentStatus;
import com.example.paymentservice.provider.vnpay.VnPayQueryRequest;
import com.example.paymentservice.provider.vnpay.VnPayQueryResponse;
import com.example.paymentservice.provider.vnpay.VnpayClient;
import com.example.paymentservice.repository.PaymentAuditLogRepository;
import com.example.paymentservice.repository.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentReconciliationScheduler {

    private final PaymentRepository paymentRepository;
    private final PaymentAuditLogRepository auditLogRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final VnpayClient vnpayClient;
    private final com.example.paymentservice.config.VnPayProperties vnPayProperties;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    private static final DateTimeFormatter VNP_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    @Value("${payment.reconciliation.pending-threshold-minutes:30}")
    private int pendingThresholdMinutes = 30;

    @Value("${payment.reconciliation.payment-batch-size:100}")
    private int batchSize = 100;

    @Value("${payment.reconciliation.payment-claim-lease-minutes:60}")
    private int claimLeaseMinutes = 60;

    private String resolveEgressIp() {
        if (vnPayProperties != null && vnPayProperties.getIpAddress() != null && !vnPayProperties.getIpAddress().isBlank()) {
            return vnPayProperties.getIpAddress();
        }
        throw new IllegalStateException("vnpay.ip-address must be configured with a valid literal egress IP address");
    }

    @Scheduled(fixedDelayString = "${payment.reconciliation.payment-scheduler-delay-ms:1800000}",
               initialDelayString = "${payment.reconciliation.payment-scheduler-initial-delay-ms:120000}")
    public void reconcilePendingPayments() {
        Instant cutoff = Instant.now().minus(pendingThresholdMinutes, ChronoUnit.MINUTES);
        log.info("Scanning for PENDING payments older than {}", cutoff);

        try {
            Instant now = Instant.now();
            List<Payment> pendingPayments = new TransactionTemplate(transactionManager).execute(status -> {
                List<Payment> claimed = paymentRepository.claimPaymentsForReconciliation(
                        PaymentStatus.PENDING.name(), cutoff, now, batchSize);
                claimed.forEach(payment -> payment.setReconciliationClaimUntil(now.plus(claimLeaseMinutes, ChronoUnit.MINUTES)));
                paymentRepository.saveAll(claimed);
                return claimed;
            });

            if (pendingPayments == null || pendingPayments.isEmpty()) {
                log.debug("No pending payments requiring reconciliation.");
                return;
            }

            log.info("Found {} pending payments for reconciliation", pendingPayments.size());
            for (Payment payment : pendingPayments) {
                reconcilePayment(payment);
            }
        } catch (Exception e) {
            log.error("Error during payment reconciliation scan: {}", e.getMessage(), e);
        }
    }

    private void reconcilePayment(Payment payment) {
        String requestId = UUID.randomUUID().toString().replace("-", "");
        VnPayQueryRequest queryRequest = VnPayQueryRequest.builder()
                .requestId(requestId)
                .txnRef(payment.getProviderTxnRef())
                .transactionDate(payment.getProviderCreateDate())
                .createDate(VNP_DATE_FORMATTER.format(Instant.now()))
                .orderInfo("Reconcile payment for order " + payment.getOrderId())
                .ipAddress(resolveEgressIp())
                .build();

        VnPayQueryResponse queryResponse = null;
        try {
            queryResponse = vnpayClient.queryTransaction(queryRequest);
        } catch (Exception e) {
            log.error("Failed to query VNPAY for payment {}: {}", payment.getId(), e.getMessage());
            return;
        }

        if (queryResponse == null || !"00".equals(queryResponse.getResponseCode())) {
            log.warn("VNPAY query returned non-success response for payment {}: {}",
                    payment.getId(), queryResponse != null ? queryResponse.getResponseCode() : "null");
            return;
        }
        BigDecimal expectedAmount = payment.getAmount().multiply(BigDecimal.valueOf(100));
        if (!payment.getProviderTxnRef().equals(queryResponse.getTxnRef())
                || !vnPayProperties.getTmnCode().equals(queryResponse.getTmnCode())
                || queryResponse.getAmount() == null
                || expectedAmount.compareTo(queryResponse.getAmount()) != 0) {
            log.warn("VNPAY query response identity or amount mismatch for payment {}", payment.getId());
            return;
        }

        final VnPayQueryResponse finalResponse = queryResponse;
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.executeWithoutResult(status -> {
            Payment p = paymentRepository.findByIdForUpdate(payment.getId()).orElse(null);
            if (p == null || p.getStatus() != PaymentStatus.PENDING) {
                return;
            }

            String txStatus = finalResponse.getTransactionStatus();
            if ("00".equals(txStatus)) {
                p.setStatus(PaymentStatus.SUCCEEDED);
                p.setProviderTransactionNo(finalResponse.getTransactionNo());
                p.setPaidAt(Instant.now());
                paymentRepository.save(p);

                recordAudit(p.getId(), "PAYMENT_RECONCILED_SUCCEEDED", PaymentStatus.PENDING.name(),
                        PaymentStatus.SUCCEEDED.name(), "SCHEDULER", "ProviderTxnNo=" + finalResponse.getTransactionNo());
                recordPaymentSucceededOutbox(p);
                log.info("Payment {} reconciled to SUCCEEDED", p.getId());
            } else if ("02".equals(txStatus)) {
                p.setStatus(PaymentStatus.FAILED);
                paymentRepository.save(p);

                recordAudit(p.getId(), "PAYMENT_RECONCILED_FAILED", PaymentStatus.PENDING.name(),
                        PaymentStatus.FAILED.name(), "SCHEDULER", "TransactionStatus=02");
                recordPaymentFailedOutbox(p);
                log.info("Payment {} reconciled to FAILED", p.getId());
            }
        });
    }

    private void recordPaymentSucceededOutbox(Payment payment) {
        PaymentSucceededEvent event = PaymentSucceededEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType("payment.succeeded")
                .eventVersion(1)
                .occurredAt(Instant.now())
                .correlationId(UUID.randomUUID().toString())
                .producer("payment-service")
                .paymentId(payment.getId())
                .orderId(payment.getOrderId())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .providerPaymentId(payment.getProviderTransactionNo())
                .paidAt(payment.getPaidAt())
                .build();
        saveOutbox("PAYMENT", payment.getOrderId().toString(), "payment.succeeded", event);
    }

    private void recordPaymentFailedOutbox(Payment payment) {
        PaymentFailedEvent event = PaymentFailedEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType("payment.failed")
                .eventVersion(1)
                .occurredAt(Instant.now())
                .correlationId(UUID.randomUUID().toString())
                .producer("payment-service")
                .paymentId(payment.getId())
                .orderId(payment.getOrderId())
                .amount(payment.getAmount())
                .failureReason("Reconciled failed from VNPAY querydr")
                .build();
        saveOutbox("PAYMENT", payment.getOrderId().toString(), "payment.failed", event);
    }

    private void saveOutbox(String aggregateType, String aggregateId, String eventType, Object payload) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .id(UUID.randomUUID())
                    .aggregateType(aggregateType)
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .eventVersion(1)
                    .payload(payloadJson)
                    .status(OutboxStatus.PENDING)
                    .retryCount(0)
                    .createdAt(Instant.now())
                    .build();
            outboxEventRepository.save(outboxEvent);
        } catch (Exception e) {
            log.error("Failed to record outbox event {}: {}", eventType, e.getMessage());
            throw new IllegalStateException("Failed to record outbox event " + eventType, e);
        }
    }

    private void recordAudit(UUID paymentId, String action, String oldStatus, String newStatus, String actor, String rawPayload) {
        String payloadJson = null;
        if (rawPayload != null) {
            try {
                payloadJson = objectMapper.writeValueAsString(rawPayload);
            } catch (Exception e) {
                payloadJson = "{\"raw\":\"" + rawPayload.replace("\"", "\\\"") + "\"}";
            }
        }
        auditLogRepository.save(PaymentAuditLog.builder()
                .paymentId(paymentId)
                .action(action)
                .oldStatus(oldStatus)
                .newStatus(newStatus)
                .actor(actor)
                .rawPayload(payloadJson)
                .occurredAt(Instant.now())
                .build());
    }
}
