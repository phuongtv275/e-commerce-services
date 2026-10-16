package com.example.identityservice.models.dto.req;

import jakarta.validation.constraints.NotBlank;

public record LoginReq(
        @NotBlank(message = "Email cannot be blank")
        String email,

        @NotBlank(message = "Password cannot be blank")
        String password
) {
    // Backward-compatibility helper
    public String username() {
        return this.email;
    }
}
