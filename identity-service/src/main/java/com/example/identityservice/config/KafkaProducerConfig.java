package com.example.identityservice.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Cấu hình Kafka Producer và Topic cho Identity Service.
 * Khởi tạo KafkaTemplate<String, String> phục vụ OutboxPublisher phát sự kiện
 * user.registered và user.password-reset-requested sang Kafka.
 */
@Configuration
public class KafkaProducerConfig {

    public static final String TOPIC_USER_REGISTERED = "user.registered";
    public static final String TOPIC_USER_PASSWORD_RESET_REQUESTED = "user.password-reset-requested";

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.producer.acks:all}")
    private String acks;

    @Value("${spring.kafka.producer.retries:3}")
    private Integer retries;

    @Bean
    @ConditionalOnMissingBean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, acks);
        props.put(ProducerConfig.RETRIES_CONFIG, retries);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    @ConditionalOnMissingBean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    public NewTopic userRegisteredTopic() {
        return TopicBuilder.name(TOPIC_USER_REGISTERED)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic userPasswordResetRequestedTopic() {
        return TopicBuilder.name(TOPIC_USER_PASSWORD_RESET_REQUESTED)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
