package com.example.paymentservice.config;

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

    @Value("${payment.kafka.consumer.initial-backoff-ms:1000}")
    private long initialBackoffMs = 1000L;

    @Value("${payment.kafka.consumer.max-backoff-ms:4000}")
    private long maxBackoffMs = 4000L;

    @Value("${payment.kafka.consumer.max-attempts:3}")
    private long maxAttempts = 3L;

    @Value("${payment.kafka.consumer.jitter-ms:200}")
    private long jitterMs = 200L;

    @Value("${payment.kafka.dlt.order-cancelled:payment.order-cancelled.dlt}")
    private String orderCancelledDltTopic = "payment.order-cancelled.dlt";

    @Value("${payment.kafka.dlt.reservation-expired:payment.reservation-expired.dlt}")
    private String reservationExpiredDltTopic = "payment.reservation-expired.dlt";

    @Value("${payment.kafka.dlt.reservation-commit-failed:payment.reservation-commit-failed.dlt}")
    private String reservationCommitFailedDltTopic = "payment.reservation-commit-failed.dlt";

    @Value("${payment.kafka.dlt.payment-late-success:payment.payment-late-success.dlt}")
    private String paymentLateSuccessDltTopic = "payment.payment-late-success.dlt";

    String resolveDltTopic(String sourceTopic) {
        return switch (sourceTopic) {
            case "order.cancelled" -> orderCancelledDltTopic;
            case "inventory.reservation-expired" -> reservationExpiredDltTopic;
            case "inventory.reservation-commit-failed" -> reservationCommitFailedDltTopic;
            case "payment.late-success" -> paymentLateSuccessDltTopic;
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
