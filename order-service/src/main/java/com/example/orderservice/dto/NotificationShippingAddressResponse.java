package com.example.orderservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationShippingAddressResponse {
    private String recipientName;
    private String addressLine1;
    private String addressLine2;
    private String district;
    private String city;
    private String country;
    private String formattedAddress;
}
