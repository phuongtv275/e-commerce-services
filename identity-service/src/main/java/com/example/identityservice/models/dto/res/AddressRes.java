package com.example.identityservice.models.dto.res;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record AddressRes(
        UUID id,
        String recipientName,
        String phone,
        String addressLine1,
        String addressLine2,
        String district,
        String city,
        String country,
        boolean isDefault,
        Instant createdAt
) {}
