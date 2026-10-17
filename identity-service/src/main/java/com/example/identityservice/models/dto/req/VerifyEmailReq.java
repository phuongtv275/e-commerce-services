package com.example.identityservice.models.dto.req;

import jakarta.validation.constraints.NotBlank;

public record VerifyEmailReq(
        @NotBlank(message = "Token must not be blank")
        String token
) {}
