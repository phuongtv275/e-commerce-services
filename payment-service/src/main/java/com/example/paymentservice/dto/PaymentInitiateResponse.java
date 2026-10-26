package com.example.paymentservice.dto;

import com.example.paymentservice.entity.PaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentInitiateResponse {

    private UUID paymentId;
    private UUID orderId;
    private PaymentStatus status;
    private String paymentUrl;
}
