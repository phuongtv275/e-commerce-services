package com.example.catalogservice.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KafkaConsumerConfigTests {

    @Test
    @DisplayName("Positive: kafkaListenerContainerFactory sets AckMode to MANUAL and configures error handler")
    @SuppressWarnings("unchecked")
    void testKafkaListenerContainerFactory_ManualAckModeAndRetryConfigured() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ConsumerFactory<Object, Object> consumerFactory = mock(ConsumerFactory.class);

        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                config.kafkaListenerContainerFactory(consumerFactory);

        assertThat(factory).isNotNull();
        assertThat(factory.getContainerProperties()).isNotNull();
        assertThat(factory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.MANUAL);
        Object errorHandler = org.springframework.test.util.ReflectionTestUtils.getField(factory, "commonErrorHandler");
        assertThat(errorHandler).isNotNull();
        assertThat(errorHandler).isInstanceOf(DefaultErrorHandler.class);
    }

    @Test
    @DisplayName("Positive: application.yaml defines spring.kafka.listener.ack-mode=manual")
    void testApplicationYaml_SpringKafkaListenerAckModeIsManual() {
        org.springframework.beans.factory.config.YamlPropertiesFactoryBean factoryBean =
                new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
        factoryBean.setResources(new org.springframework.core.io.ClassPathResource("application.yaml"));
        java.util.Properties properties = factoryBean.getObject();

        assertThat(properties).isNotNull();
        assertThat(properties.getProperty("spring.kafka.listener.ack-mode")).isEqualTo("manual");
    }
}
