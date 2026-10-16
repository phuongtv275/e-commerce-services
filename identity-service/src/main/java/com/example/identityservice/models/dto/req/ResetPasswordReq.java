package com.example.identityservice.models.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ResetPasswordReq(
        @NotBlank(message = "Token must not be blank")
        String token,

        @NotBlank(message = "New password must not be blank")
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]{8,}$",
                message = "New password must be at least 8 characters long and contain at least one uppercase letter, one lowercase letter, one digit, and one special character"
        )
        String newPassword
) {}
