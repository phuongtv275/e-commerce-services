package com.example.identityservice.models.dto.req;

import jakarta.validation.constraints.NotBlank;

public record RefreshTokenReq(
        @NotBlank(message = "Refresh token must not be blank")
        String refreshToken
) {}
