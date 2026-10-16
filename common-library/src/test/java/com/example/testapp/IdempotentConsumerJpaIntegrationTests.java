package com.example.testapp;

import com.ecommerce.common.config.EnableCommonJpaRepositories;
import com.ecommerce.common.idempotency.IdempotentConsumerHelper;
import com.ecommerce.common.idempotency.ProcessedEvent;
import com.ecommerce.common.idempotency.ProcessedEventRepository;
import com.ecommerce.common.idempotency.ProcessedStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = IdempotentConsumerJpaIntegrationTests.TestApplication.class,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.application.name=test-app"
        })
public class IdempotentConsumerJpaIntegrationTests {

    @SpringBootApplication(scanBasePackages = "com.example.testapp")
    @EnableCommonJpaRepositories
    static class TestApplication {
    }

    @Autowired
    private IdempotentConsumerHelper idempotentConsumerHelper;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("Positive & Integration: Host app ở package com.example tự động scan repository và entity của com.ecommerce.common")
    void testPackageScanningAcrossPackages_Positive() {
        assertNotNull(processedEventRepository);
        assertNotNull(idempotentConsumerHelper);
    }

    @Test
    @DisplayName("Positive & Integration: Xung đột claim trong transaction không làm rollback-only transaction của caller")
    void testClaimCollisionDoesNotRollbackCallerTransaction_Positive() {
        String eventId = "integration-event-1";
        String consumerGroup = "test-group";

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        // Lần 1: Xử lý thành công trong transaction 1
        transactionTemplate.execute(status -> {
            boolean processed = idempotentConsumerHelper.processIdempotent(eventId, consumerGroup, "Payload", p -> {});
            assertTrue(processed);
            return null;
        });

        // Lần 2: Gọi lại cùng eventId trong transaction 2.
        // Phải trả về false và transaction 2 commit thành công KHÔNG bị UnexpectedRollbackException
        AtomicBoolean committed = new AtomicBoolean(false);
        assertDoesNotThrow(() -> {
            transactionTemplate.execute(status -> {
                boolean processed = idempotentConsumerHelper.processIdempotent(eventId, consumerGroup, "Payload", p -> {
                    fail("Consumer logic should not be executed on duplicate");
                });
                assertFalse(processed);
                committed.set(true);
                return null;
            });
        });

        assertTrue(committed.get());
    }

    @Test
    @DisplayName("Positive & Integration: Đồng thời 2 luồng cùng claim 1 eventId, đúng 1 luồng thắng và luồng thua không bị lỗi rollback-only")
    void testConcurrentClaimsOnRealDatabase_Positive() throws Exception {
        String eventId = "concurrent-event-" + System.currentTimeMillis();
        String consumerGroup = "concurrent-group";

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicInteger exceptionCount = new AtomicInteger(0);

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    transactionTemplate.execute(status -> {
                        boolean processed = idempotentConsumerHelper.processIdempotent(
                                eventId, consumerGroup, "Data", p -> {
                                    try {
                                        Thread.sleep(10);
                                    } catch (InterruptedException ignored) {}
                                });
                        if (processed) {
                            successCount.incrementAndGet();
                        } else {
                            failureCount.incrementAndGet();
                        }
                        return null;
                    });
                } catch (Exception e) {
                    exceptionCount.incrementAndGet();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // Phát lệnh chạy đồng thời
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(0, exceptionCount.get(), "Không được có luồng nào ném ngoại lệ rollback-only");
        assertEquals(1, successCount.get(), "Chính xác 1 luồng claim thành công");
        assertEquals(1, failureCount.get(), "Chính xác 1 luồng thua cuộc và trả về false an toàn");
    }

    @Test
    @DisplayName("Positive & Integration: Khi consumer logic gặp lỗi ném ngoại lệ, claim được thu hồi ngay lập tức để cho phép retry")
    void testLogicFailureRollsBackClaimAtomically_Positive() {
        String eventId = "failure-event-" + System.currentTimeMillis();
        String consumerGroup = "failure-group";

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        // Lần 1: Nghiệp vụ ném ngoại lệ
        assertThrows(RuntimeException.class, () -> {
            transactionTemplate.execute(status -> {
                idempotentConsumerHelper.processIdempotent(eventId, consumerGroup, "Data", p -> {
                    throw new RuntimeException("Simulated business error or DB failure");
                });
                return null;
            });
        });

        // Kiểm tra: Claim PROCESSING bị thu hồi ngay lập tức
        boolean existsAfterRollback = processedEventRepository.existsByEventIdAndConsumerGroup(eventId, consumerGroup);
        assertFalse(existsAfterRollback, "Bản ghi claim phải bị xóa để Kafka có thể retry ngay lập tức");

        // Lần 2: Thử lại (retry) phải thành công
        AtomicBoolean retriedSuccess = new AtomicBoolean(false);
        transactionTemplate.execute(status -> {
            boolean processed = idempotentConsumerHelper.processIdempotent(eventId, consumerGroup, "Data", p -> {
                retriedSuccess.set(true);
            });
            assertTrue(processed);
            return null;
        });

        assertTrue(retriedSuccess.get(), "Retry sau lỗi phải thực thi thành công");
    }

    @Test
    @DisplayName("Positive & Integration: Khi caller transaction rollback sau khi callback thành công, COMPLETED claim cũng bị rollback đồng bộ")
    void testCallerTransactionRollback_RollsBackCompletedClaim_Positive() {
        String eventId = "caller-rollback-" + System.currentTimeMillis();
        String consumerGroup = "rollback-group";

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        // Giả lập: Callback chạy thành công nhưng transaction bên ngoài bị rollback sau đó
        transactionTemplate.execute(status -> {
            boolean processed = idempotentConsumerHelper.processIdempotent(eventId, consumerGroup, "Data", p -> {});
            assertTrue(processed);
            status.setRollbackOnly(); // Ép caller transaction rollback
            return null;
        });

        // Trạng thái trong DB không được là COMPLETED (vì đã bị rollback cùng caller)
        var recordOpt = processedEventRepository.findByEventIdAndConsumerGroup(eventId, consumerGroup);
        if (recordOpt.isPresent()) {
            assertNotEquals(ProcessedStatus.COMPLETED, recordOpt.get().getStatus(),
                    "Claim tuyệt đối không được ở trạng thái COMPLETED khi transaction của caller bị rollback");
        }
    }

    @Test
    @DisplayName("Positive & Integration: Lease hết hạn được tranh chấp nguyên tử bằng conditional update, chỉ 1 worker thắng")
    void testExpiredLeaseReclaimAtomic_OnlyOneWorkerWins() throws Exception {
        String eventId = "expired-lease-" + System.currentTimeMillis();
        String consumerGroup = "lease-group";

        // Tạo sẵn 1 claim PROCESSING đã hết hạn từ 10 giây trước (mô phỏng worker cũ bị crash)
        ProcessedEvent expiredRecord = ProcessedEvent.builder()
                .id(UUID.randomUUID())
                .eventId(eventId)
                .consumerGroup(consumerGroup)
                .status(ProcessedStatus.PROCESSING)
                .expiresAt(Instant.now().minusSeconds(10))
                .processedAt(Instant.now().minusSeconds(70))
                .build();
        processedEventRepository.saveAndFlush(expiredRecord);

        // 2 workers mới cùng phát hiện lease hết hạn và tranh chấp giành quyền xử lý
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    transactionTemplate.execute(status -> {
                        boolean processed = idempotentConsumerHelper.processIdempotent(
                                eventId, consumerGroup, "Data", p -> {});
                        if (processed) {
                            successCount.incrementAndGet();
                        } else {
                            failureCount.incrementAndGet();
                        }
                        return null;
                    });
                } catch (Exception ignored) {}
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(1, successCount.get(), "Chính xác 1 worker giành được quyền xử lý lease hết hạn");
        assertEquals(1, failureCount.get(), "Worker còn lại bị từ chối do lease đã được worker kia gia hạn");
    }
}
