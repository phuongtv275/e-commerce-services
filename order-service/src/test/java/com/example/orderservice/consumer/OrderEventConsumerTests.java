package com.example.orderservice.consumer;

import com.ecommerce.common.idempotency.IdempotentConsumerHelper;
import com.example.orderservice.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class OrderEventConsumerTests {

    @Test
    void missingOrderIdOnCommitFailedIsRetriedInsteadOfAcknowledged() {
        OrderEventConsumer consumer = new OrderEventConsumer(
                mock(OrderService.class), mock(IdempotentConsumerHelper.class), new ObjectMapper());
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        String payload = "{\"eventId\":\"" + UUID.randomUUID()
                + "\",\"eventType\":\"inventory.reservation-commit-failed\",\"eventVersion\":1}";
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "inventory.reservation-commit-failed", 0, 0L, UUID.randomUUID().toString(), payload);

        assertThatThrownBy(() -> consumer.consumeReservationCommitFailed(record, acknowledgment))
                .isInstanceOf(RuntimeException.class);
        verify(acknowledgment, never()).acknowledge();
    }
}
