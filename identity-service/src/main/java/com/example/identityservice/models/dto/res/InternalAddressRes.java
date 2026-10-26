package com.example.identityservice.models.dto.res;

import lombok.Builder;

import java.util.UUID;

@Builder
public record InternalAddressRes(
        UUID id,
        UUID userId,
        String recipientName,
        String phone,
        String addressLine1,
        String addressLine2,
        String district,
        String city,
        String country
) {}
