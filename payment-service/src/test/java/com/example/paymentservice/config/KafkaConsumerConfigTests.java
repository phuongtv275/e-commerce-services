package com.example.paymentservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.backoff.BackOffExecution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KafkaConsumerConfigTests {

    @Test
    void configuresManualAcknowledgmentAndThreeJitteredRetries() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                config.kafkaListenerContainerFactory(mock(ConsumerFactory.class), mock(KafkaTemplate.class));

        assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL);
        var backOff = config.createBackOff();
        assertThat(backOff.getInitialInterval()).isEqualTo(1000L);
        assertThat(backOff.getMaxInterval()).isEqualTo(4000L);
        assertThat(backOff.getMaxAttempts()).isEqualTo(3L);
        assertThat(backOff.getJitter()).isEqualTo(200L);
        DefaultErrorHandler errorHandler = (DefaultErrorHandler) ReflectionTestUtils.getField(factory, "commonErrorHandler");
        Object failureTracker = ReflectionTestUtils.getField(errorHandler, "failureTracker");
        assertThat(ReflectionTestUtils.getField(failureTracker, "recoverer"))
                .isInstanceOf(DeadLetterPublishingRecoverer.class);
        BackOffExecution execution = backOff.start();
        assertThat(execution.nextBackOff()).isBetween(1000L, 1200L);
        assertThat(execution.nextBackOff()).isBetween(1600L, 2400L);
        assertThat(execution.nextBackOff()).isBetween(3200L, 4000L);
        assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }

    @Test
    void routesSagaEventsToPaymentServiceDltTopics() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();

        assertThat(config.resolveDltTopic("order.cancelled")).isEqualTo("payment.order-cancelled.dlt");
        assertThat(config.resolveDltTopic("inventory.reservation-expired"))
                .isEqualTo("payment.reservation-expired.dlt");
        assertThat(config.resolveDltTopic("inventory.reservation-commit-failed"))
                .isEqualTo("payment.reservation-commit-failed.dlt");
    }

    @Test
    void letsKafkaSelectDltPartition() {
        assertThat(new KafkaConsumerConfig().resolveDltPartition("inventory.reservation-expired").partition())
                .isEqualTo(-1);
    }
}
