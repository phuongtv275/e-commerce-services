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
public class VnPayPaymentUrlRequest {
    private String txnRef;
    private BigDecimal amount;
    private String orderInfo;
    private String ipAddress;
    private String createDate;
    private String expireDate;
}
