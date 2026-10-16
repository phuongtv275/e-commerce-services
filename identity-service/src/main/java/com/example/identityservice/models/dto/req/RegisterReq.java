package com.example.identityservice.models.dto.req;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterReq(
        @NotBlank(message = "Full name cannot be blank")
        @Size(min = 2, max = 255, message = "Full name must be between 2 and 255 characters")
        String fullName,

        @NotBlank(message = "Email cannot be blank")
        @Email(message = "Invalid email format")
        String email,

        @NotBlank(message = "Password cannot be blank")
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]{8,}$",
                message = "Password must be at least 8 characters long and contain at least one uppercase letter, one lowercase letter, one digit, and one special character"
        )
        String password,

        @Pattern(regexp = "^(0|\\+84)[0-9]{9}$", message = "Invalid phone number format")
        String phoneNumber
) {
    // Backward-compatibility constructors & helpers
    public RegisterReq(String fullName, String email, String password) {
        this(fullName, email, password, null);
    }

    public String username() {
        return this.email;
    }
}
