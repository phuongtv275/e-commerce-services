package com.example.identityservice.models.dto.req;

import jakarta.validation.constraints.NotBlank;

public record LogoutReq(
        @NotBlank(message = "Token must not be blank")
        String token
) {}
