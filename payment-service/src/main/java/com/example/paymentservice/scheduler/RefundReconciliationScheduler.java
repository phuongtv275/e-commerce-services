package com.example.paymentservice.scheduler;

import com.example.paymentservice.entity.Refund;
import com.example.paymentservice.entity.RefundStatus;
import com.example.paymentservice.refund.service.RefundService;
import com.example.paymentservice.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RefundReconciliationScheduler {

    private final RefundRepository refundRepository;
    private final RefundService refundService;
    private final PlatformTransactionManager transactionManager;

    @Value("${payment.reconciliation.refund-interval-minutes:15}")
    private int refundIntervalMinutes = 15;

    @Value("${payment.reconciliation.max-retries:3}")
    private int maxRetries = 3;

    @Value("${payment.reconciliation.batch-size:50}")
    private int batchSize = 50;

    @Value("${payment.reconciliation.refund-claim-lease-minutes:30}")
    private int claimLeaseMinutes = 30;

    @Scheduled(fixedDelayString = "${payment.reconciliation.refund-scheduler-delay-ms:900000}",
               initialDelayString = "${payment.reconciliation.refund-scheduler-initial-delay-ms:60000}")
    public void reconcilePendingRefunds() {
        Instant cutoff = Instant.now().minus(refundIntervalMinutes, ChronoUnit.MINUTES);
        log.info("Scanning for PENDING refunds older than {} with retryCount < {}", cutoff, maxRetries);

        try {
            Instant now = Instant.now();
            List<Refund> pendingRefunds = new TransactionTemplate(transactionManager).execute(status -> {
                List<Refund> claimed = refundRepository.claimRefundsForReconciliation(
                        RefundStatus.PENDING.name(), cutoff, maxRetries, now, batchSize);
                claimed.forEach(refund -> refund.setReconciliationClaimUntil(now.plus(claimLeaseMinutes, ChronoUnit.MINUTES)));
                refundRepository.saveAll(claimed);
                return claimed;
            });

            if (pendingRefunds == null || pendingRefunds.isEmpty()) {
                log.debug("No pending refunds requiring reconciliation.");
                return;
            }

            log.info("Found {} pending refunds for reconciliation", pendingRefunds.size());
            for (Refund refund : pendingRefunds) {
                try {
                    refundService.reconcilePendingRefund(refund.getId());
                } catch (Exception e) {
                    log.error("Failed to reconcile refund {}: {}", refund.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Error during refund reconciliation scan: {}", e.getMessage(), e);
        }
    }
}
