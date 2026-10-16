package com.ecommerce.common.idempotency;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.function.Consumer;

@Slf4j
@Component
public class IdempotentConsumerHelper {

    private final ProcessedEventRepository processedEventRepository;
    private final IdempotentClaimManager claimManager;

    public IdempotentConsumerHelper(ProcessedEventRepository processedEventRepository) {
        this(processedEventRepository, new IdempotentClaimManager(processedEventRepository));
    }

    public IdempotentConsumerHelper(ProcessedEventRepository processedEventRepository,
                                  IdempotentClaimManager claimManager) {
        this.processedEventRepository = processedEventRepository;
        this.claimManager = claimManager != null ? claimManager : new IdempotentClaimManager(processedEventRepository);
    }

    public <T> boolean processIdempotent(String eventId, String consumerGroup, T payload, Consumer<T> consumerLogic) {
        if (!claimManager.claim(eventId, consumerGroup)) {
            log.info("Event id={} was already processed or claimed by consumer group {}. Skipping duplicate message.",
                    eventId, consumerGroup);
            return false;
        }

        // Đăng ký TransactionSynchronization để nếu caller transaction bị rollback (kể cả sau khi helper trả về),
        // claim PROCESSING sẽ được tự động giải phóng ngay lập tức, giúp Kafka retry tức thì mà không bị chặn duplicate
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                        log.info("Caller transaction rolled back. Releasing claim for event id={} in group {}",
                                eventId, consumerGroup);
                        claimManager.release(eventId, consumerGroup);
                    }
                }
            });
        }

        try {
            consumerLogic.accept(payload);
            claimManager.complete(eventId, consumerGroup);
            log.debug("Successfully processed and completed event id={} for consumer group {}", eventId, consumerGroup);
            return true;
        } catch (RuntimeException | Error e) {
            log.warn("Business logic failed for event id={} in consumer group {}. Releasing claim to allow retry.",
                    eventId, consumerGroup);
            claimManager.release(eventId, consumerGroup);
            throw e;
        }
    }
}
