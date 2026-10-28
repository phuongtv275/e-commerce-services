package com.example.notificationservice.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KafkaConsumerConfigTests {

    @Test
    @DisplayName("consumerFactory configures bootstrap servers, group id, String deserializers, and disables auto commit")
    void configuresConsumerFactoryProperties() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");
        ReflectionTestUtils.setField(config, "groupId", "notification-service-group");
        ReflectionTestUtils.setField(config, "autoOffsetReset", "earliest");

        ConsumerFactory<String, String> consumerFactory = config.consumerFactory();
        assertThat(consumerFactory).isNotNull();

        Map<String, Object> props = consumerFactory.getConfigurationProperties();
        assertThat(props.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG)).isEqualTo("localhost:9092");
        assertThat(props.get(ConsumerConfig.GROUP_ID_CONFIG)).isEqualTo("notification-service-group");
        assertThat(props.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG)).isEqualTo("earliest");
        assertThat(props.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isEqualTo(false);
        assertThat(props.get(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG)).isEqualTo(StringDeserializer.class);
        assertThat(props.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG)).isEqualTo(StringDeserializer.class);
    }

    @Test
    @DisplayName("kafkaListenerContainerFactory configures MANUAL_IMMEDIATE ack mode, non-fatal missing topics, and error handler")
    @SuppressWarnings("unchecked")
    void configuresKafkaListenerContainerFactory() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ConsumerFactory<String, String> mockConsumerFactory = mock(ConsumerFactory.class);
        KafkaTemplate<Object, Object> mockKafkaTemplate = mock(KafkaTemplate.class);

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                config.kafkaListenerContainerFactory(mockConsumerFactory, mockKafkaTemplate, 3, 1000L, 4000L, ".dlt");

        assertThat(factory).isNotNull();
        assertThat(factory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        assertThat(factory.getContainerProperties().isMissingTopicsFatal()).isFalse();

        Object commonErrorHandler = ReflectionTestUtils.getField(factory, "commonErrorHandler");
        assertThat(commonErrorHandler).isInstanceOf(DefaultErrorHandler.class);

        Object failureTracker = ReflectionTestUtils.getField(commonErrorHandler, "failureTracker");
        assertThat(failureTracker).isNotNull();
        assertThat(ReflectionTestUtils.getField(failureTracker, "recoverer"))
                .isInstanceOf(DeadLetterPublishingRecoverer.class);
    }

    @Test
    @DisplayName("kafkaListenerContainerFactory fallbacks to DefaultErrorHandler without DLT when kafkaTemplate is null")
    @SuppressWarnings("unchecked")
    void configuresKafkaListenerContainerFactoryWithoutTemplate() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ConsumerFactory<String, String> mockConsumerFactory = mock(ConsumerFactory.class);

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                config.kafkaListenerContainerFactory(mockConsumerFactory, null, 3, 1000L, 4000L, ".dlt");

        assertThat(factory).isNotNull();
        assertThat(factory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        assertThat(factory.getContainerProperties().isMissingTopicsFatal()).isFalse();

        Object commonErrorHandler = ReflectionTestUtils.getField(factory, "commonErrorHandler");
        assertThat(commonErrorHandler).isInstanceOf(DefaultErrorHandler.class);
        Object failureTracker = ReflectionTestUtils.getField(commonErrorHandler, "failureTracker");
        assertThat(ReflectionTestUtils.getField(failureTracker, "recoverer"))
                .isNotInstanceOf(DeadLetterPublishingRecoverer.class);
    }
}
