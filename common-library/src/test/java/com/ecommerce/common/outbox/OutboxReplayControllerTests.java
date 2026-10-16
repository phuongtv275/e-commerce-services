package com.ecommerce.common.outbox;

import com.ecommerce.common.exception.ConflictException;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.ecommerce.common.response.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxReplayControllerTests {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private OutboxReplayController controller;

    @BeforeEach
    void setUp() {
        controller = new OutboxReplayController(outboxEventRepository);
    }

    @Test
    @DisplayName("Positive: Replay sự kiện DEAD_LETTER thành công đưa về PENDING với retryCount=0")
    void testReplayDeadLetterEvent_Positive() {
        UUID eventId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.builder()
                .id(eventId)
                .aggregateType("ORDER")
                .aggregateId("order-123")
                .status(OutboxStatus.DEAD_LETTER)
                .retryCount(10)
                .lastError("Timeout error")
                .build();

        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<ApiResponse<OutboxEvent>> response = controller.replayOutboxEvent(eventId);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(OutboxStatus.PENDING, response.getBody().getData().getStatus());
        assertEquals(0, response.getBody().getData().getRetryCount());
        assertNull(response.getBody().getData().getLastError());
        verify(outboxEventRepository).save(event);
    }

    @Test
    @DisplayName("Negative: Replay sự kiện không tồn tại ném ResourceNotFoundException")
    void testReplayNotFound_Negative() {
        UUID eventId = UUID.randomUUID();
        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> controller.replayOutboxEvent(eventId));
    }

    @Test
    @DisplayName("Negative: Replay sự kiện không phải trạng thái DEAD_LETTER ném ConflictException")
    void testReplayNonDeadLetter_Negative() {
        UUID eventId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.builder()
                .id(eventId)
                .status(OutboxStatus.PUBLISHED)
                .build();

        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.of(event));

        assertThrows(ConflictException.class, () -> controller.replayOutboxEvent(eventId));
    }
}
