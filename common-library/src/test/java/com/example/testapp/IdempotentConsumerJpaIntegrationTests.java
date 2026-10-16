package com.example.testapp;

import com.ecommerce.common.config.EnableCommonJpaRepositories;
import com.ecommerce.common.idempotency.IdempotentConsumerHelper;
import com.ecommerce.common.idempotency.ProcessedEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicBoolean;

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
}
