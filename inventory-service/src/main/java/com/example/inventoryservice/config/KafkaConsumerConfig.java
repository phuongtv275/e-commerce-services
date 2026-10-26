package com.example.inventoryservice.config;

import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
@ConditionalOnClass(ConcurrentKafkaListenerContainerFactory.class)
public class KafkaConsumerConfig {

    @Value("${inventory.kafka.consumer.initial-backoff-ms:1000}")
    private long initialBackoffMs = 1000L;

    @Value("${inventory.kafka.consumer.max-backoff-ms:4000}")
    private long maxBackoffMs = 4000L;

    @Value("${inventory.kafka.consumer.max-attempts:3}")
    private long maxAttempts = 3L;

    @Value("${inventory.kafka.consumer.jitter-ms:200}")
    private long jitterMs = 200L;

    @Value("${inventory.kafka.dlt.payment-succeeded:inventory.payment-succeeded.dlt}")
    private String paymentSucceededDltTopic = "inventory.payment-succeeded.dlt";

    @Value("${inventory.kafka.dlt.payment-failed:inventory.payment-failed.dlt}")
    private String paymentFailedDltTopic = "inventory.payment-failed.dlt";

    @Value("${inventory.kafka.dlt.order-cancelled:inventory.order-cancelled.dlt}")
    private String orderCancelledDltTopic = "inventory.order-cancelled.dlt";

    String resolveDltTopic(String sourceTopic) {
        if (sourceTopic == null) {
            return paymentSucceededDltTopic;
        }
        return switch (sourceTopic) {
            case "payment.succeeded" -> paymentSucceededDltTopic;
            case "payment.failed" -> paymentFailedDltTopic;
            case "order.cancelled" -> orderCancelledDltTopic;
            default -> sourceTopic + ".dlt";
        };
    }

    TopicPartition resolveDltPartition(String sourceTopic) {
        return new TopicPartition(resolveDltTopic(sourceTopic), -1);
    }

    ExponentialBackOff createBackOff() {
        ExponentialBackOff backOff = new ExponentialBackOff(initialBackoffMs, 2.0);
        backOff.setMaxInterval(maxBackoffMs);
        backOff.setMaxAttempts(maxAttempts);
        backOff.setJitter(jitterMs);

        long sumIntervals = 0;
        long current = initialBackoffMs;
        for (int i = 0; i < maxAttempts; i++) {
            sumIntervals += current;
            current = Math.min((long) (current * 2.0), maxBackoffMs);
        }
        backOff.setMaxElapsedTime(sumIntervals);
        return backOff;
    }

    @Bean
    @ConditionalOnBean(ConsumerFactory.class)
    public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConsumerFactory<Object, Object> consumerFactory,
            @Autowired(required = false) KafkaTemplate<Object, Object> kafkaTemplate) {

        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.getContainerProperties().setDeliveryAttemptHeader(true);

        ExponentialBackOff backOff = createBackOff();

        DefaultErrorHandler errorHandler;
        if (kafkaTemplate != null) {
            DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> resolveDltPartition(record.topic()));
            recoverer.setFailIfSendResultIsError(true);
            errorHandler = new DefaultErrorHandler(recoverer, backOff);
        } else {
            errorHandler = new DefaultErrorHandler(backOff);
        }

        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
