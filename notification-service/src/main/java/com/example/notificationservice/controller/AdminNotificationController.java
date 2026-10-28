package com.example.notificationservice.controller;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.ecommerce.common.response.ApiResponse;
import com.ecommerce.common.response.PagedResponse;
import com.example.notificationservice.domain.NotificationLog;
import com.example.notificationservice.domain.NotificationStatus;
import com.example.notificationservice.dto.NotificationDetailResponse;
import com.example.notificationservice.dto.NotificationSummaryResponse;
import com.example.notificationservice.dto.RedriveRequest;
import com.example.notificationservice.dto.RedriveResponse;
import com.example.notificationservice.repository.NotificationLogRepository;
import com.example.notificationservice.service.DlqRedriveWorker;
import com.example.notificationservice.service.RecipientMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@RestController
@RequestMapping("/api/v1/admin/notifications")
@PreAuthorize("hasAuthority('ROLE_ADMINISTRATOR')")
@RequiredArgsConstructor
public class AdminNotificationController {

    private final NotificationLogRepository notificationLogRepository;
    private final DlqRedriveWorker dlqRedriveWorker;

    private static final String REDACTED_TOKEN = "[REDACTED_TOKEN]";
    private static final Pattern BODY_TOKEN_PATTERN = Pattern.compile(
            "(?i)((?:token|resetToken)(?:=|%3D|\\s+|:\\s*))([a-zA-Z0-9_.-]+)"
    );
    private static final Pattern PAYLOAD_TOKEN_PATTERN = Pattern.compile(
            "(?i)(\"(?:verificationToken|resetToken|token)\"\\s*:\\s*\")[^\"]*(\")"
    );

    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<NotificationSummaryResponse>>> getHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Page<NotificationLog> logs = notificationLogRepository.findAll(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "queuedAt"))
        );

        List<NotificationSummaryResponse> content = logs.getContent().stream()
                .map(this::toSummaryResponse)
                .toList();

        PagedResponse<NotificationSummaryResponse> pagedResponse = PagedResponse.<NotificationSummaryResponse>builder()
                .content(content)
                .pageNumber(logs.getNumber())
                .pageSize(logs.getSize())
                .totalElements(logs.getTotalElements())
                .totalPages(logs.getTotalPages())
                .isFirst(logs.isFirst())
                .isLast(logs.isLast())
                .build();

        return ResponseEntity.ok(ApiResponse.success("Get notification history successfully", pagedResponse));
    }

    @GetMapping("/dlq")
    public ResponseEntity<ApiResponse<PagedResponse<NotificationSummaryResponse>>> getDlqNotifications(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Page<NotificationLog> logs = notificationLogRepository.findByStatus(
                NotificationStatus.DEAD_LETTER,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"))
        );

        List<NotificationSummaryResponse> content = logs.getContent().stream()
                .map(this::toSummaryResponse)
                .toList();

        PagedResponse<NotificationSummaryResponse> pagedResponse = PagedResponse.<NotificationSummaryResponse>builder()
                .content(content)
                .pageNumber(logs.getNumber())
                .pageSize(logs.getSize())
                .totalElements(logs.getTotalElements())
                .totalPages(logs.getTotalPages())
                .isFirst(logs.isFirst())
                .isLast(logs.isLast())
                .build();

        return ResponseEntity.ok(ApiResponse.success("Get DLQ notifications successfully", pagedResponse));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<NotificationDetailResponse>> getNotificationDetail(@PathVariable UUID id) {
        NotificationLog notification = notificationLogRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found with id: " + id));

        NotificationDetailResponse response = toDetailResponse(notification);
        return ResponseEntity.ok(ApiResponse.success("Get notification detail successfully", response));
    }

    @PostMapping("/redrive-dlq")
    public ResponseEntity<ApiResponse<RedriveResponse>> redriveDlq(
            @RequestBody(required = false) RedriveRequest request
    ) {
        int accepted = 0;
        int skipped = 0;
        Map<String, String> details = new HashMap<>();

        if (request != null && request.getIds() != null && !request.getIds().isEmpty()) {
            for (UUID id : request.getIds()) {
                Optional<NotificationLog> opt = notificationLogRepository.findById(id);
                if (opt.isEmpty()) {
                    skipped++;
                    details.put(id.toString(), "NOT_FOUND");
                    continue;
                }

                NotificationLog job = opt.get();
                if ("[PURGED_PII]".equals(job.getSubject())) {
                    throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "Cannot redrive purged notification " + id);
                }

                if (job.getStatus() != NotificationStatus.DEAD_LETTER && job.getStatus() != NotificationStatus.REDRIVING) {
                    skipped++;
                    details.put(id.toString(), "INVALID_STATUS_" + job.getStatus());
                    continue;
                }

                boolean success = dlqRedriveWorker.redriveSingle(id);
                if (success) {
                    accepted++;
                    details.put(id.toString(), "ACCEPTED");
                } else {
                    skipped++;
                    details.put(id.toString(), "CLAIM_OR_DELIVERY_FAILED");
                }
            }
        } else {
            // Batch redrive without specific ids
            accepted = dlqRedriveWorker.redriveBatch(50);
            details.put("batch", "Processed batch of size " + accepted);
        }

        RedriveResponse redriveResponse = RedriveResponse.builder()
                .acceptedCount(accepted)
                .skippedCount(skipped)
                .details(details)
                .build();

        return ResponseEntity.ok(ApiResponse.success("Redrive DLQ completed", redriveResponse));
    }

    private NotificationSummaryResponse toSummaryResponse(NotificationLog log) {
        return NotificationSummaryResponse.builder()
                .id(log.getId())
                .eventId(log.getEventId())
                .eventType(log.getEventType())
                .channel(log.getChannel())
                .maskedRecipient(RecipientMasker.mask(log.getRecipient()))
                .subject(log.getSubject())
                .retryCount(log.getRetryCount())
                .status(log.getStatus())
                .errorMessage(log.getErrorMessage())
                .queuedAt(log.getQueuedAt())
                .updatedAt(log.getUpdatedAt())
                .correlationId(log.getCorrelationId())
                .build();
    }

    private NotificationDetailResponse toDetailResponse(NotificationLog log) {
        return NotificationDetailResponse.builder()
                .id(log.getId())
                .eventId(log.getEventId())
                .eventType(log.getEventType())
                .channel(log.getChannel())
                .recipient(log.getRecipient())
                .subject(log.getSubject())
                .renderedBody(sanitizeBody(log.getRenderedBody()))
                .bodyPreview(sanitizeBody(log.getBodyPreview()))
                .payload(sanitizePayload(log.getPayload()))
                .status(log.getStatus())
                .retryCount(log.getRetryCount())
                .errorMessage(log.getErrorMessage())
                .correlationId(log.getCorrelationId())
                .queuedAt(log.getQueuedAt())
                .sentAt(log.getSentAt())
                .updatedAt(log.getUpdatedAt())
                .build();
    }

    private String sanitizeBody(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        return BODY_TOKEN_PATTERN.matcher(content).replaceAll("$1" + REDACTED_TOKEN);
    }

    private String sanitizePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return payload;
        }
        return PAYLOAD_TOKEN_PATTERN.matcher(payload).replaceAll("$1" + REDACTED_TOKEN + "$2");
    }
}
