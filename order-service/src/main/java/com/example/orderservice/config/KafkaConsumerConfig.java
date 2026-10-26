package com.example.orderservice.config;

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

    @Value("${order.kafka.consumer.initial-backoff-ms:1000}")
    private long initialBackoffMs = 1000L;

    @Value("${order.kafka.consumer.max-backoff-ms:4000}")
    private long maxBackoffMs = 4000L;

    @Value("${order.kafka.consumer.max-attempts:3}")
    private long maxAttempts = 3L;

    @Value("${order.kafka.consumer.jitter-ms:200}")
    private long jitterMs = 200L;

    @Value("${order.kafka.dlt.payment-succeeded:order.payment-succeeded.dlt}")
    private String paymentSucceededDltTopic = "order.payment-succeeded.dlt";

    @Value("${order.kafka.dlt.payment-failed:order.payment-failed.dlt}")
    private String paymentFailedDltTopic = "order.payment-failed.dlt";

    @Value("${order.kafka.dlt.reservation-expired:order.reservation-expired.dlt}")
    private String reservationExpiredDltTopic = "order.reservation-expired.dlt";

    @Value("${order.kafka.dlt.reservation-commit-failed:order.reservation-commit-failed.dlt}")
    private String reservationCommitFailedDltTopic = "order.reservation-commit-failed.dlt";

    String resolveDltTopic(String sourceTopic) {
        return switch (sourceTopic) {
            case "payment.succeeded" -> paymentSucceededDltTopic;
            case "payment.failed" -> paymentFailedDltTopic;
            case "inventory.reservation-expired" -> reservationExpiredDltTopic;
            case "inventory.reservation-commit-failed" -> reservationCommitFailedDltTopic;
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
        backOff.setMaxElapsedTime(initialBackoffMs + Math.min(initialBackoffMs * 2, maxBackoffMs)
                + maxBackoffMs + 2 * jitterMs);
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

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> resolveDltPartition(record.topic()));
        recoverer.setFailIfSendResultIsError(true);
        factory.setCommonErrorHandler(new DefaultErrorHandler(recoverer, createBackOff()));
        return factory;
    }
}
