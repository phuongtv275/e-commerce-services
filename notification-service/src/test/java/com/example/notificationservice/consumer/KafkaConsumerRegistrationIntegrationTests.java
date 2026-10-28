package com.example.notificationservice.consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class KafkaConsumerRegistrationIntegrationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Test
    @DisplayName("Kafka infrastructure beans are loaded in Spring ApplicationContext")
    void verifiesKafkaBeansExist() {
        assertThat(applicationContext.containsBean("consumerFactory")).isTrue();
        assertThat(applicationContext.containsBean("kafkaListenerContainerFactory")).isTrue();
        assertThat(applicationContext.containsBean("notificationKafkaConsumer")).isTrue();

        Object consumerFactory = applicationContext.getBean("consumerFactory");
        assertThat(consumerFactory).isInstanceOf(ConsumerFactory.class);

        Object containerFactory = applicationContext.getBean("kafkaListenerContainerFactory");
        assertThat(containerFactory).isInstanceOf(ConcurrentKafkaListenerContainerFactory.class);
    }

    @Test
    @DisplayName("KafkaListener container registers notification-service-group and all 6 domain topics")
    void verifiesKafkaListenerRegistrationAndTopics() {
        Collection<MessageListenerContainer> containers = registry.getListenerContainers();
        assertThat(containers).isNotEmpty();

        MessageListenerContainer container = containers.stream()
                .filter(c -> "notification-service-group".equals(c.getGroupId()))
                .findFirst()
                .orElse(null);

        assertThat(container).as("Container for notification-service-group must be registered").isNotNull();
        assertThat(container.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        String[] topics = container.getContainerProperties().getTopics();
        assertThat(topics).isNotNull();
        List<String> topicList = Arrays.asList(topics);

        assertThat(topicList).containsExactlyInAnyOrder(
                "user.registered",
                "order.status-changed",
                "order.cancelled",
                "payment.succeeded",
                "payment.refund-completed",
                "user.password-reset-requested"
        );
    }
}
