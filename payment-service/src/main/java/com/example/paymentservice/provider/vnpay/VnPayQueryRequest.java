package com.example.paymentservice.provider.vnpay;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VnPayQueryRequest {
    private String requestId;
    private String txnRef;
    private String transactionDate;
    private String createDate;
    private String ipAddress;
    private String orderInfo;
}
