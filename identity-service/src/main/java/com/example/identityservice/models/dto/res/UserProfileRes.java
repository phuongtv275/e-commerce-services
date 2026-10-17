package com.example.identityservice.models.dto.res;

import com.example.identityservice.models.constants.UserStatus;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder
public record UserProfileRes(
        UUID id,
        String email,
        String fullName,
        String phoneNumber,
        UserStatus status,
        boolean emailVerified,
        List<String> roles,
        Instant createdAt
) {}
