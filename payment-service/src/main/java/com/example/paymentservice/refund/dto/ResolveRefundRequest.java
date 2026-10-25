package com.example.paymentservice.refund.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResolveRefundRequest {

    @NotNull(message = "Resolution is required")
    private RefundResolution resolution;

    @Size(max = 15, message = "Provider refund ID cannot exceed 15 characters")
    private String providerRefundId;

    @Size(max = 500, message = "Note cannot exceed 500 characters")
    private String note;
}
