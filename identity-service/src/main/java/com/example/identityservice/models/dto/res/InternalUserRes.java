package com.example.identityservice.models.dto.res;

import com.example.identityservice.models.constants.UserStatus;
import lombok.Builder;

import java.util.List;
import java.util.UUID;

@Builder
public record InternalUserRes(
        UUID id,
        String email,
        String fullName,
        String phoneNumber,
        UserStatus status,
        boolean emailVerified,
        List<String> roles
) {}
