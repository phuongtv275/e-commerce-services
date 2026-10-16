package com.example.identityservice.models.dto.req;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateProfileReq(
        @Size(min = 2, max = 255, message = "Full name must be between 2 and 255 characters")
        String fullName,

        @Pattern(regexp = "^(0|\\+84)[0-9]{9}$", message = "Invalid phone number format")
        String phoneNumber
) {}
