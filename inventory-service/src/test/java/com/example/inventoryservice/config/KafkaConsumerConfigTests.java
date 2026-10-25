package com.example.inventoryservice.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KafkaConsumerConfigTests {

    @Test
    @DisplayName("Positive: kafkaListenerContainerFactory sets AckMode to MANUAL and configures default retry backoff and STOP")
    @SuppressWarnings("unchecked")
    void testKafkaListenerContainerFactory_ManualAckModeAndRetryConfigured() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ConsumerFactory<Object, Object> consumerFactory = mock(ConsumerFactory.class);

        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                config.kafkaListenerContainerFactory(consumerFactory, null);

        assertThat(factory).isNotNull();
        assertThat(factory.getContainerProperties()).isNotNull();
        assertThat(factory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.MANUAL);
        assertThat(factory.getContainerProperties().isDeliveryAttemptHeader()).isTrue();

        Object errorHandler = ReflectionTestUtils.getField(factory, "commonErrorHandler");
        assertThat(errorHandler).isNotNull();
        assertThat(errorHandler).isInstanceOf(DefaultErrorHandler.class);

        ExponentialBackOff backOff = config.createBackOff();
        assertThat(backOff.getInitialInterval()).isEqualTo(100L);
        assertThat(backOff.getMaxInterval()).isEqualTo(400L);
        assertThat(backOff.getMaxAttempts()).isEqualTo(3L);
        assertThat(backOff.getMaxElapsedTime()).isEqualTo(700L); // 100 + 200 + 400

        BackOffExecution execution = backOff.start();
        assertThat(execution.nextBackOff()).isEqualTo(100L);
        assertThat(execution.nextBackOff()).isEqualTo(200L);
        assertThat(execution.nextBackOff()).isEqualTo(400L);
        assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }

    @Test
    @DisplayName("Positive: kafkaListenerContainerFactory honors configured 250/2000/5 retry values and STOP")
    @SuppressWarnings("unchecked")
    void testKafkaListenerContainerFactory_HonorsConfiguredRetryValues() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ReflectionTestUtils.setField(config, "initialBackoffMs", 250L);
        ReflectionTestUtils.setField(config, "maxBackoffMs", 2000L);
        ReflectionTestUtils.setField(config, "maxAttempts", 5L);

        ExponentialBackOff backOff = config.createBackOff();
        assertThat(backOff.getInitialInterval()).isEqualTo(250L);
        assertThat(backOff.getMaxInterval()).isEqualTo(2000L);
        assertThat(backOff.getMaxAttempts()).isEqualTo(5L);
        assertThat(backOff.getMaxElapsedTime()).isEqualTo(5750L); // 250 + 500 + 1000 + 2000 + 2000

        BackOffExecution execution = backOff.start();
        assertThat(execution.nextBackOff()).isEqualTo(250L);
        assertThat(execution.nextBackOff()).isEqualTo(500L);
        assertThat(execution.nextBackOff()).isEqualTo(1000L);
        assertThat(execution.nextBackOff()).isEqualTo(2000L);
        assertThat(execution.nextBackOff()).isEqualTo(2000L);
        assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }

    @Test
    @DisplayName("Positive: application.yaml defines spring.kafka.listener.ack-mode=manual and retry properties")
    void testApplicationYaml_SpringKafkaListenerAckModeIsManual() {
        YamlPropertiesFactoryBean factoryBean = new YamlPropertiesFactoryBean();
        factoryBean.setResources(new ClassPathResource("application.yaml"));
        Properties properties = factoryBean.getObject();

        assertThat(properties).isNotNull();
        assertThat(properties.getProperty("spring.kafka.listener.ack-mode")).isEqualTo("manual");
        assertThat(properties.getProperty("inventory.kafka.consumer-group")).isEqualTo("inventory-reservation-group");
        assertThat(properties.getProperty("inventory.kafka.consumer.max-attempts")).isEqualTo("3");
        assertThat(properties.getProperty("inventory.kafka.consumer.initial-backoff-ms")).isEqualTo("100");
        assertThat(properties.getProperty("inventory.kafka.consumer.max-backoff-ms")).isEqualTo("400");
    }

    @Test
    @DisplayName("Regression: resolveDltTopic routes records to source-specific DLT topics, not payment-succeeded DLT")
    void testResolveDltTopic_RoutesToAppropriateDlt() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();

        assertThat(config.resolveDltTopic("payment.succeeded")).isEqualTo("inventory.payment-succeeded.dlt");
        assertThat(config.resolveDltTopic("payment.failed")).isEqualTo("inventory.payment-failed.dlt");
        assertThat(config.resolveDltTopic("order.cancelled")).isEqualTo("inventory.order-cancelled.dlt");
    }

    @Test
    @DisplayName("Positive: When KafkaTemplate is present, DeadLetterPublishingRecoverer is configured with failIfSendResultIsError=true")
    @SuppressWarnings("unchecked")
    void testKafkaListenerContainerFactory_WithKafkaTemplate_ConfiguresDltRecovererWithFailOnError() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ConsumerFactory<Object, Object> consumerFactory = mock(ConsumerFactory.class);
        KafkaTemplate<Object, Object> kafkaTemplate = mock(KafkaTemplate.class);

        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                config.kafkaListenerContainerFactory(consumerFactory, kafkaTemplate);

        DefaultErrorHandler errorHandler = (DefaultErrorHandler) ReflectionTestUtils.getField(factory, "commonErrorHandler");
        assertThat(errorHandler).isNotNull();

        Object rawFailureTracker = ReflectionTestUtils.getField(errorHandler, "failureTracker");
        DeadLetterPublishingRecoverer recoverer = (DeadLetterPublishingRecoverer) ReflectionTestUtils.getField(rawFailureTracker, "recoverer");
        assertThat(recoverer).isNotNull();

        Boolean failOnError = (Boolean) ReflectionTestUtils.getField(recoverer, "failIfSendResultIsError");
        assertThat(failOnError).isTrue();
    }
}
