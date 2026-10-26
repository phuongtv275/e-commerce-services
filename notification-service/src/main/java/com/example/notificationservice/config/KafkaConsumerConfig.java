package com.example.notificationservice.config;

import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Configuration
@ConditionalOnClass(ConcurrentKafkaListenerContainerFactory.class)
public class KafkaConsumerConfig {

    @Bean
    @ConditionalOnBean(ConsumerFactory.class)
    public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConsumerFactory<Object, Object> consumerFactory,
            @Autowired(required = false) KafkaTemplate<Object, Object> kafkaTemplate,
            @Value("${notification.kafka.consumer.max-attempts:3}") int maxAttempts,
            @Value("${notification.kafka.consumer.initial-backoff-ms:1000}") long initialBackoffMs,
            @Value("${notification.kafka.consumer.max-backoff-ms:4000}") long maxBackoffMs,
            @Value("${notification.kafka.consumer.dlt-suffix:.dlt}") String dltSuffix) {

        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(Math.max(0, maxAttempts - 1));
        backOff.setInitialInterval(initialBackoffMs);
        backOff.setMaxInterval(maxBackoffMs);

        DefaultErrorHandler errorHandler;
        if (kafkaTemplate != null) {
            DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                    (record, ex) -> new TopicPartition(record.topic() + dltSuffix, record.partition()));
            errorHandler = new DefaultErrorHandler(recoverer, backOff);
        } else {
            errorHandler = new DefaultErrorHandler(backOff);
        }

        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
