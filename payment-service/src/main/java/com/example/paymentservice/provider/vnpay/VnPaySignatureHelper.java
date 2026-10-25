package com.example.paymentservice.provider.vnpay;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class VnPaySignatureHelper {

    private VnPaySignatureHelper() {}

    /**
     * Compute HMAC-SHA512 of data using the secret key, returning lowercase hex string.
     */
    public static String hmacSha512(String key, String data) {
        if (key == null || data == null) {
            throw new IllegalArgumentException("Key and data must not be null for HMAC-SHA512");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA512");
            mac.init(secretKeySpec);
            byte[] bytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(2 * bytes.length);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA512", e);
        }
    }

    /**
     * Build canonical hash data for VNPAY v2.1.0 payment URL and IPN:
     * Sort parameter keys ascending, exclude vnp_SecureHash and vnp_SecureHashType,
     * URL-encode both key and value with UTF-8, and concatenate with '&'.
     */
    public static String buildHashData(Map<String, String> params) {
        List<String> fieldNames = new ArrayList<>(params.keySet());
        Collections.sort(fieldNames);
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String fieldName : fieldNames) {
            String fieldValue = params.get(fieldName);
            if (fieldValue != null && !fieldValue.isEmpty()
                    && !"vnp_SecureHash".equals(fieldName)
                    && !"vnp_SecureHashType".equals(fieldName)) {
                if (!first) {
                    sb.append('&');
                }
                sb.append(URLEncoder.encode(fieldName, StandardCharsets.UTF_8))
                  .append('=')
                  .append(URLEncoder.encode(fieldValue, StandardCharsets.UTF_8));
                first = false;
            }
        }
        return sb.toString();
    }

    /**
     * Build canonical response hash string for VNPAY v2.1.0 refund response:
     * Format: vnp_ResponseId|vnp_Command|vnp_ResponseCode|vnp_Message|vnp_TmnCode|vnp_TxnRef|vnp_Amount|vnp_BankCode|vnp_PayDate|vnp_TransactionNo|vnp_TransactionType|vnp_TransactionStatus|vnp_OrderInfo
     * ALWAYS ends at OrderInfo per official VNPAY specification.
     */
    public static String buildRefundResponseHashData(
            String responseId,
            String command,
            String responseCode,
            String message,
            String tmnCode,
            String txnRef,
            String amount,
            String bankCode,
            String payDate,
            String transactionNo,
            String transactionType,
            String transactionStatus,
            String orderInfo
    ) {
        return String.join("|",
                safeValue(responseId),
                safeValue(command),
                safeValue(responseCode),
                safeValue(message),
                safeValue(tmnCode),
                safeValue(txnRef),
                safeValue(amount),
                safeValue(bankCode),
                safeValue(payDate),
                safeValue(transactionNo),
                safeValue(transactionType),
                safeValue(transactionStatus),
                safeValue(orderInfo)
        );
    }

    /**
     * Build canonical response hash string for VNPAY v2.1.0 querydr response:
     * Format: vnp_ResponseId|vnp_Command|vnp_ResponseCode|vnp_Message|vnp_TmnCode|vnp_TxnRef|vnp_Amount|vnp_BankCode|vnp_PayDate|vnp_TransactionNo|vnp_TransactionType|vnp_TransactionStatus|vnp_OrderInfo|vnp_PromotionCode|vnp_PromotionAmount
     * Always preserves the 15 pipe-delimited fields with empty values when promotionCode/promotionAmount are absent.
     */
    public static String buildQueryResponseHashData(
            String responseId,
            String command,
            String responseCode,
            String message,
            String tmnCode,
            String txnRef,
            String amount,
            String bankCode,
            String payDate,
            String transactionNo,
            String transactionType,
            String transactionStatus,
            String orderInfo,
            String promotionCode,
            String promotionAmount
    ) {
        return String.join("|",
                safeValue(responseId),
                safeValue(command),
                safeValue(responseCode),
                safeValue(message),
                safeValue(tmnCode),
                safeValue(txnRef),
                safeValue(amount),
                safeValue(bankCode),
                safeValue(payDate),
                safeValue(transactionNo),
                safeValue(transactionType),
                safeValue(transactionStatus),
                safeValue(orderInfo),
                safeValue(promotionCode),
                safeValue(promotionAmount)
        );
    }

    private static String safeValue(String val) {
        return val != null ? val : "";
    }

    /**
     * Constant-time comparison of two hex hashes to prevent timing attacks.
     */
    public static boolean constantTimeEquals(String hash1, String hash2) {
        if (hash1 == null || hash2 == null) {
            return false;
        }
        byte[] aBytes = hash1.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = hash2.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(aBytes, bBytes);
    }
}
