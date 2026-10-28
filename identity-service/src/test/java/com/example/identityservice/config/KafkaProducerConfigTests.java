package com.example.identityservice.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaProducerConfigTests {

    @Test
    @DisplayName("Positive: producerFactory creates properly configured ProducerFactory with String serializers")
    void testProducerFactoryConfiguration() {
        KafkaProducerConfig config = new KafkaProducerConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");
        ReflectionTestUtils.setField(config, "acks", "all");
        ReflectionTestUtils.setField(config, "retries", 3);

        ProducerFactory<String, String> factory = config.producerFactory();
        assertThat(factory).isNotNull();

        Map<String, Object> configs = factory.getConfigurationProperties();
        assertThat(configs.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG)).isEqualTo("localhost:9092");
        assertThat(configs.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG)).isEqualTo(StringSerializer.class);
        assertThat(configs.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG)).isEqualTo(StringSerializer.class);
        assertThat(configs.get(ProducerConfig.ACKS_CONFIG)).isEqualTo("all");
        assertThat(configs.get(ProducerConfig.RETRIES_CONFIG)).isEqualTo(3);
    }

    @Test
    @DisplayName("Positive: kafkaTemplate bean is instantiated from ProducerFactory")
    void testKafkaTemplateBean() {
        KafkaProducerConfig config = new KafkaProducerConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");
        ReflectionTestUtils.setField(config, "acks", "all");
        ReflectionTestUtils.setField(config, "retries", 3);

        ProducerFactory<String, String> factory = config.producerFactory();
        KafkaTemplate<String, String> template = config.kafkaTemplate(factory);

        assertThat(template).isNotNull();
        assertThat(template.getProducerFactory()).isSameAs(factory);
    }

    @Test
    @DisplayName("Positive: userRegisteredTopic and userPasswordResetRequestedTopic defined with 3 partitions and 1 replica")
    void testTopicsConfiguration() {
        KafkaProducerConfig config = new KafkaProducerConfig();

        NewTopic userRegTopic = config.userRegisteredTopic();
        assertThat(userRegTopic).isNotNull();
        assertThat(userRegTopic.name()).isEqualTo("user.registered");
        assertThat(userRegTopic.numPartitions()).isEqualTo(3);
        assertThat(userRegTopic.replicationFactor()).isEqualTo((short) 1);

        NewTopic pwdResetTopic = config.userPasswordResetRequestedTopic();
        assertThat(pwdResetTopic).isNotNull();
        assertThat(pwdResetTopic.name()).isEqualTo("user.password-reset-requested");
        assertThat(pwdResetTopic.numPartitions()).isEqualTo(3);
        assertThat(pwdResetTopic.replicationFactor()).isEqualTo((short) 1);
    }
}
