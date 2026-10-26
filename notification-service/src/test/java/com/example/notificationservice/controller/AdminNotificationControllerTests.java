package com.example.notificationservice.controller;

import com.ecommerce.common.exception.GlobalExceptionHandler;
import com.example.notificationservice.domain.NotificationChannel;
import com.example.notificationservice.domain.NotificationLog;
import com.example.notificationservice.domain.NotificationStatus;
import com.example.notificationservice.dto.RedriveRequest;
import com.example.notificationservice.repository.NotificationLogRepository;
import com.example.notificationservice.security.AccessDenied;
import com.example.notificationservice.security.JwtEntryPoint;
import com.example.notificationservice.service.DlqRedriveWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminNotificationController.class)
@Import({GlobalExceptionHandler.class, JwtEntryPoint.class, AccessDenied.class, AdminNotificationControllerTests.MethodSecurityTestConfig.class})
class AdminNotificationControllerTests {

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationLogRepository notificationLogRepository;

    @MockitoBean
    private DlqRedriveWorker dlqRedriveWorker;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Positive: GET /api/v1/admin/notifications/dlq with ROLE_ADMINISTRATOR returns masked recipient")
    @WithMockUser(authorities = {"ROLE_ADMINISTRATOR"})
    void testGetDlq_Authorized() throws Exception {
        UUID id = UUID.randomUUID();
        NotificationLog logEntry = NotificationLog.builder()
                .id(id)
                .eventId("evt-dlq-1")
                .eventType("order.cancelled")
                .channel(NotificationChannel.EMAIL)
                .recipient("john.doe@example.com")
                .subject("Test DLQ Subject")
                .retryCount(3)
                .status(NotificationStatus.DEAD_LETTER)
                .errorMessage("SMTP connection timeout")
                .queuedAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(notificationLogRepository.findByStatus(eq(NotificationStatus.DEAD_LETTER), any()))
                .thenReturn(new PageImpl<>(List.of(logEntry)));

        mockMvc.perform(get("/api/v1/admin/notifications/dlq"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content[0].id").value(id.toString()))
                .andExpect(jsonPath("$.data.content[0].maskedRecipient").value("j***e@example.com"))
                .andExpect(jsonPath("$.data.content[0].status").value("DEAD_LETTER"));
    }

    @Test
    @DisplayName("Negative: GET /api/v1/admin/notifications/dlq without ROLE_ADMINISTRATOR returns 403 Forbidden")
    @WithMockUser(authorities = {"ROLE_CUSTOMER"})
    void testGetDlq_Forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/notifications/dlq"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Positive: GET /api/v1/admin/notifications/{id} with ROLE_ADMINISTRATOR returns detail")
    @WithMockUser(authorities = {"ROLE_ADMINISTRATOR"})
    void testGetDetail_Success() throws Exception {
        UUID id = UUID.randomUUID();
        NotificationLog logEntry = NotificationLog.builder()
                .id(id)
                .eventId("evt-detail-1")
                .eventType("payment.succeeded")
                .channel(NotificationChannel.EMAIL)
                .recipient("admin.view@example.com")
                .subject("Biên nhận thanh toán")
                .renderedBody("<p>Nội dung đầy đủ</p>")
                .status(NotificationStatus.SENT)
                .queuedAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(notificationLogRepository.findById(id)).thenReturn(Optional.of(logEntry));

        mockMvc.perform(get("/api/v1/admin/notifications/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.recipient").value("admin.view@example.com"))
                .andExpect(jsonPath("$.data.renderedBody").value("<p>Nội dung đầy đủ</p>"));
    }

    @Test
    @DisplayName("Negative: GET /api/v1/admin/notifications/{id} with non-existent id returns 404 NOT_FOUND")
    @WithMockUser(authorities = {"ROLE_ADMINISTRATOR"})
    void testGetDetail_NotFound() throws Exception {
        UUID nonExistent = UUID.randomUUID();
        when(notificationLogRepository.findById(nonExistent)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/admin/notifications/{id}", nonExistent))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("Positive: POST /api/v1/admin/notifications/redrive-dlq redrives batch")
    @WithMockUser(authorities = {"ROLE_ADMINISTRATOR"})
    void testRedriveDlq_BatchSuccess() throws Exception {
        when(dlqRedriveWorker.redriveBatch(50)).thenReturn(3);

        mockMvc.perform(post("/api/v1/admin/notifications/redrive-dlq")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.acceptedCount").value(3));
    }

    @Test
    @DisplayName("Negative: POST /api/v1/admin/notifications/redrive-dlq on purged record returns 422 BUSINESS_RULE_VIOLATION")
    @WithMockUser(authorities = {"ROLE_ADMINISTRATOR"})
    void testRedriveDlq_PurgedRecordReturns422() throws Exception {
        UUID id = UUID.randomUUID();
        NotificationLog purgedLog = NotificationLog.builder()
                .id(id)
                .subject("[PURGED_PII]")
                .status(NotificationStatus.DEAD_LETTER)
                .build();

        when(notificationLogRepository.findById(id)).thenReturn(Optional.of(purgedLog));

        RedriveRequest req = RedriveRequest.builder().ids(List.of(id)).build();

        mockMvc.perform(post("/api/v1/admin/notifications/redrive-dlq")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }
}
