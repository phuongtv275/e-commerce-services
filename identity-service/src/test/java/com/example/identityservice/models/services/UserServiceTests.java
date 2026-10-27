package com.example.identityservice.models.services;

import com.example.identityservice.models.dto.req.UpdateProfileReq;
import com.example.identityservice.models.dto.res.UserProfileRes;
import com.example.identityservice.models.entities.User;
import com.example.identityservice.models.repositories.UserRepository;
import com.example.identityservice.models.services.impl.UserServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTests {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuditLogService auditLogService;

    private ObjectMapper objectMapper;
    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        userService = new UserServiceImpl(userRepository, auditLogService, objectMapper);
    }

    @Test
    @DisplayName("ISSUE-01: Updating profile with special characters (quotes, backslashes) serializes valid JSON in AuditLog")
    void testUpdateProfileWithSpecialCharactersProducesValidJsonAuditLog() throws Exception {
        UUID userId = UUID.randomUUID();
        User existingUser = User.builder()
                .id(userId)
                .email("special@example.com")
                .fullName("Original Name")
                .phoneNumber("0912345678")
                .build();

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(existingUser));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String trickyFullName = "John \"The Special\\Slash\" Doe \t\n 'quotes'";
        UpdateProfileReq req = new UpdateProfileReq(trickyFullName, "0999888777");

        UserProfileRes res = userService.updateProfile(userId, req);

        assertThat(res.fullName()).isEqualTo(trickyFullName);

        ArgumentCaptor<String> deltaCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).logAction(
                eq(userId),
                eq("special@example.com"),
                eq("UPDATE_PROFILE"),
                eq("USER"),
                eq(userId.toString()),
                deltaCaptor.capture(),
                isNull(),
                any()
        );

        String capturedDelta = deltaCaptor.getValue();
        assertThat(capturedDelta).isNotNull();

        // Must deserialize as valid JSON without throwing syntax error
        Map<String, Object> deltaMap = objectMapper.readValue(capturedDelta, new TypeReference<Map<String, Object>>() {});
        assertThat(deltaMap).containsEntry("fullName", trickyFullName);
    }
}
