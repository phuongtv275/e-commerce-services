package com.ecommerce.common.outbox;

import com.ecommerce.common.exception.ConflictException;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.ecommerce.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
public class OutboxReplayController {

    private final OutboxEventRepository outboxEventRepository;

    @PostMapping({"/api/v1/admin/outbox/replay/{id}", "/internal/outbox/replay/{id}"})
    @PreAuthorize("hasAnyRole('ADMINISTRATOR', 'SERVICE_ADMIN', 'SERVICE_ORDER', 'SERVICE_PAYMENT', 'SERVICE_INVENTORY')")
    public ResponseEntity<ApiResponse<OutboxEvent>> replayOutboxEvent(@PathVariable UUID id) {
        OutboxEvent event = outboxEventRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("OutboxEvent", id));

        if (event.getStatus() != OutboxStatus.DEAD_LETTER) {
            throw new ConflictException(String.format(
                    "Chỉ có thể replay sự kiện ở trạng thái DEAD_LETTER. Trạng thái hiện tại: %s",
                    event.getStatus()
            ));
        }

        event.setStatus(OutboxStatus.PENDING);
        event.setRetryCount(0);
        event.setNextRetryAt(Instant.now());
        event.setLastError(null);

        OutboxEvent savedEvent = outboxEventRepository.save(event);
        log.info("Outbox event id={} reset to PENDING by admin replay request", id);

        return ResponseEntity.ok(ApiResponse.success(
                "Sự kiện đã được đưa lại vào hàng đợi xử lý thành công",
                savedEvent
        ));
    }
}
