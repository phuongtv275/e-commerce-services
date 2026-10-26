package com.example.paymentservice.refund.dto;

import com.example.paymentservice.entity.Refund;
import com.example.paymentservice.entity.RefundStatus;
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
public class RefundResponse {

    private UUID id;
    private UUID paymentId;
    private UUID orderId;
    private BigDecimal amount;
    private String reason;
    private RefundStatus status;
    private UUID requestedBy;
    private String providerRequestId;
    private String idempotencyKey;
    private String providerTransactionNo;
    private int retryCount;
    private Instant processedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public static RefundResponse fromEntity(Refund refund) {
        if (refund == null) {
            return null;
        }
        return RefundResponse.builder()
                .id(refund.getId())
                .paymentId(refund.getPaymentId())
                .orderId(refund.getOrderId())
                .amount(refund.getAmount())
                .reason(refund.getReason())
                .status(refund.getStatus())
                .requestedBy(refund.getRequestedBy())
                .providerRequestId(refund.getProviderRequestId())
                .idempotencyKey(refund.getIdempotencyKey())
                .providerTransactionNo(refund.getProviderTransactionNo())
                .retryCount(refund.getRetryCount())
                .processedAt(refund.getProcessedAt())
                .createdAt(refund.getCreatedAt())
                .updatedAt(refund.getUpdatedAt())
                .build();
    }
}
