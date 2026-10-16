package com.example.identityservice.models.dto.req;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ForgotPasswordReq(
        @NotBlank(message = "Email must not be blank")
        @Email(message = "Invalid email format")
        String email
) {}
