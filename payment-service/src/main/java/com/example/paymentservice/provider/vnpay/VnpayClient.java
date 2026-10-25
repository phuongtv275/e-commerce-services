package com.example.paymentservice.provider.vnpay;

import java.util.Map;

public interface VnpayClient {

    /**
     * Build signed VNPAY v2.1.0 payment redirect URL.
     */
    String createPaymentUrl(VnPayPaymentUrlRequest request);

    /**
     * Verify HMAC-SHA512 checksum of incoming IPN query parameters.
     */
    boolean verifyIpnSignature(Map<String, String> queryParams);

    /**
     * Invoke VNPAY merchant transaction API for refund (full or partial).
     */
    VnPayRefundResponse refund(VnPayRefundRequest request);

    /**
     * Invoke VNPAY merchant transaction API querydr for payment reconciliation.
     */
    VnPayQueryResponse queryTransaction(VnPayQueryRequest request);
}
