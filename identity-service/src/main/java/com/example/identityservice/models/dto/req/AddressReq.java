package com.example.identityservice.models.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AddressReq(
        @NotBlank(message = "Recipient name cannot be blank")
        String recipientName,

        @NotBlank(message = "Phone cannot be blank")
        @Pattern(regexp = "^(0|\\+84)[0-9]{9}$", message = "Invalid phone number format")
        String phone,

        @NotBlank(message = "Address line 1 cannot be blank")
        String addressLine1,

        String addressLine2,

        @NotBlank(message = "District cannot be blank")
        String district,

        @NotBlank(message = "City cannot be blank")
        String city,

        String country,

        Boolean isDefault
) {}
