package com.example.paymentservice.scheduler;

import com.example.paymentservice.entity.Refund;
import com.example.paymentservice.entity.RefundStatus;
import com.example.paymentservice.refund.service.RefundService;
import com.example.paymentservice.repository.RefundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefundReconciliationSchedulerTests {

    @Mock
    private RefundRepository refundRepository;

    @Mock
    private RefundService refundService;

    @Mock
    private PlatformTransactionManager transactionManager;

    @InjectMocks
    private RefundReconciliationScheduler scheduler;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(org.mockito.Mockito.mock(TransactionStatus.class));
        ReflectionTestUtils.setField(scheduler, "refundIntervalMinutes", 15);
        ReflectionTestUtils.setField(scheduler, "maxRetries", 3);
        ReflectionTestUtils.setField(scheduler, "batchSize", 50);
    }

    @Test
    @DisplayName("Positive: Scheduler invokes reconcilePendingRefund on each pending refund")
    void reconcilePendingRefunds_processesRefunds() {
        UUID refundId1 = UUID.randomUUID();
        UUID refundId2 = UUID.randomUUID();

        Refund r1 = Refund.builder().id(refundId1).status(RefundStatus.PENDING).build();
        Refund r2 = Refund.builder().id(refundId2).status(RefundStatus.PENDING).build();

        when(refundRepository.claimRefundsForReconciliation(eq(RefundStatus.PENDING.name()), any(Instant.class), eq(3), any(Instant.class), eq(50)))
                .thenReturn(List.of(r1, r2));

        scheduler.reconcilePendingRefunds();

        verify(refundService, times(1)).reconcilePendingRefund(refundId1);
        verify(refundService, times(1)).reconcilePendingRefund(refundId2);
        org.assertj.core.api.Assertions.assertThat(r1.getReconciliationClaimUntil()).isNotNull();
    }

    @Test
    @DisplayName("Positive: Scheduler handles empty pending list cleanly")
    void reconcilePendingRefunds_emptyList() {
        when(refundRepository.claimRefundsForReconciliation(eq(RefundStatus.PENDING.name()), any(Instant.class), eq(3), any(Instant.class), eq(50)))
                .thenReturn(Collections.emptyList());

        scheduler.reconcilePendingRefunds();

        verify(refundService, never()).reconcilePendingRefund(any());
    }
}
