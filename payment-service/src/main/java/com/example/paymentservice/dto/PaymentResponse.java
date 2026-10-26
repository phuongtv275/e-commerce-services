package com.example.paymentservice.dto;

import com.example.paymentservice.entity.PaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResponse {

    private UUID paymentId;
    private UUID orderId;
    private UUID customerId;
    private BigDecimal amount;
    private String currency;
    private String provider;
    private PaymentStatus status;
    private String providerTxnRef;
    private String providerTransactionNo;
    private String paymentUrl;
    private Instant paidAt;
    private Instant createdAt;
    private Instant updatedAt;
}
