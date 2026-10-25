package com.example.orderservice.client.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdentityAddressResponse {
    private UUID id;
    private UUID userId;
    private String recipientName;
    private String phone;
    private String addressLine1;
    private String addressLine2;
    private String district;
    private String city;
    private String country;
}
