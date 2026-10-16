package com.ecommerce.common.idempotency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotentConsumerHelperTests {

    @Mock
    private ProcessedEventRepository processedEventRepository;

    private IdempotentConsumerHelper helper;

    @BeforeEach
    void setUp() {
        helper = new IdempotentConsumerHelper(processedEventRepository);
    }

    @Test
    @DisplayName("Positive: Sự kiện lần đầu xuất hiện thực thi business logic và lưu vết vào processed_events")
    void testFirstTimeEvent_ExecutesBusinessLogic_Positive() {
        String eventId = "event-uuid-100";
        String consumerGroup = "order-fulfillment-group";

        when(processedEventRepository.existsByEventIdAndConsumerGroup(eventId, consumerGroup))
                .thenReturn(false);

        AtomicBoolean executed = new AtomicBoolean(false);
        boolean result = helper.processIdempotent(eventId, consumerGroup, "PayloadData", payload -> {
            executed.set(true);
            assertEquals("PayloadData", payload);
        });

        assertTrue(result);
        assertTrue(executed.get());
        verify(processedEventRepository).saveAndFlush(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("Negative: Sự kiện trùng lặp trong cùng consumer group bị bỏ qua, không chạy business logic")
    void testDuplicateEvent_Skipped_Negative() {
        String eventId = "event-uuid-100";
        String consumerGroup = "order-fulfillment-group";

        when(processedEventRepository.existsByEventIdAndConsumerGroup(eventId, consumerGroup))
                .thenReturn(true);

        AtomicBoolean executed = new AtomicBoolean(false);
        boolean result = helper.processIdempotent(eventId, consumerGroup, "PayloadData", payload -> {
            executed.set(true);
        });

        assertFalse(result);
        assertFalse(executed.get());
        verify(processedEventRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Negative: Xung đột đồng thời (DataIntegrityViolationException) được bắt và trả về false an toàn")
    void testConcurrentExecution_HandledGracefully_Negative() {
        String eventId = "event-uuid-101";
        String consumerGroup = "order-fulfillment-group";

        when(processedEventRepository.existsByEventIdAndConsumerGroup(eventId, consumerGroup))
                .thenReturn(false);
        when(processedEventRepository.saveAndFlush(any(ProcessedEvent.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate key violation"));

        AtomicBoolean executed = new AtomicBoolean(false);
        boolean result = helper.processIdempotent(eventId, consumerGroup, "PayloadData", payload -> {
            executed.set(true);
        });

        assertFalse(result);
        assertTrue(executed.get()); // Logic chạy nhưng DB báo đã có luồng khác insert trước
    }

    @Test
    @DisplayName("Positive: Cùng 1 eventId nhưng thuộc 2 consumer group khác nhau đều được xử lý độc lập")
    void testDifferentConsumerGroups_BothExecute_Positive() {
        String eventId = "event-uuid-common";
        String groupA = "order-service-group";
        String groupB = "notification-service-group";

        when(processedEventRepository.existsByEventIdAndConsumerGroup(eventId, groupA)).thenReturn(false);
        when(processedEventRepository.existsByEventIdAndConsumerGroup(eventId, groupB)).thenReturn(false);

        AtomicInteger executionCount = new AtomicInteger(0);

        boolean resultA = helper.processIdempotent(eventId, groupA, "Data", p -> executionCount.incrementAndGet());
        boolean resultB = helper.processIdempotent(eventId, groupB, "Data", p -> executionCount.incrementAndGet());

        assertTrue(resultA);
        assertTrue(resultB);
        assertEquals(2, executionCount.get());
        verify(processedEventRepository, times(2)).saveAndFlush(any(ProcessedEvent.class));
    }
}
