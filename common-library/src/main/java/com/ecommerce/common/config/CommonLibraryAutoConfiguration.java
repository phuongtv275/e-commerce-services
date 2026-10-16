package com.ecommerce.common.config;

import com.ecommerce.common.exception.GlobalExceptionHandler;
import com.ecommerce.common.idempotency.IdempotentConsumerHelper;
import com.ecommerce.common.idempotency.ProcessedEventRepository;
import com.ecommerce.common.outbox.OutboxEventRepository;
import com.ecommerce.common.outbox.OutboxPublisher;
import com.ecommerce.common.outbox.OutboxReplayController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;

@AutoConfiguration
public class CommonLibraryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ProcessedEventRepository.class)
    public IdempotentConsumerHelper idempotentConsumerHelper(ProcessedEventRepository processedEventRepository) {
        return new IdempotentConsumerHelper(processedEventRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({OutboxEventRepository.class, KafkaTemplate.class})
    public OutboxPublisher outboxPublisher(OutboxEventRepository outboxEventRepository,
                                           KafkaTemplate<String, String> kafkaTemplate) {
        return new OutboxPublisher(outboxEventRepository, kafkaTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(OutboxEventRepository.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public OutboxReplayController outboxReplayController(OutboxEventRepository outboxEventRepository) {
        return new OutboxReplayController(outboxEventRepository);
    }
}
