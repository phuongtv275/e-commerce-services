package com.example.paymentservice.provider.vnpay;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VnPayRefundRequest {
    private String requestId;
    private String txnRef;
    private BigDecimal amount;
    private String transactionType;
    private String transactionNo;
    private String transactionDate;
    private String createBy;
    private String ipAddress;
    private String orderInfo;
}
