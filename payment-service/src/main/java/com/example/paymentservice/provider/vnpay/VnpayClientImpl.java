package com.example.paymentservice.provider.vnpay;

import com.example.paymentservice.config.VnPayProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class VnpayClientImpl implements VnpayClient {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId GMT7_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final VnPayProperties properties;
    private final RestClient restClient;

    @Override
    public String createPaymentUrl(VnPayPaymentUrlRequest request) {
        properties.validate();

        if (request.getIpAddress() == null || request.getIpAddress().isBlank()) {
            throw new IllegalArgumentException("Client IP address is required for VNPAY payment URL generation");
        }
        VnPayProperties.validateLiteralIpAddress(request.getIpAddress(), "vnp_IpAddr");

        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Payment amount must be greater than zero");
        }

        long amountInCents = request.getAmount().multiply(BigDecimal.valueOf(100)).longValueExact();

        String createDate = request.getCreateDate();
        if (createDate == null || createDate.isBlank()) {
            createDate = LocalDateTime.now(GMT7_ZONE).format(DATE_FORMATTER);
        }

        String expireDate = request.getExpireDate();
        if (expireDate == null || expireDate.isBlank()) {
            LocalDateTime createdTime = LocalDateTime.parse(createDate, DATE_FORMATTER);
            expireDate = createdTime.plusMinutes(properties.getExpireMinutes()).format(DATE_FORMATTER);
        }

        Map<String, String> vnpParams = new HashMap<>();
        vnpParams.put("vnp_Version", properties.getVersion());
        vnpParams.put("vnp_Command", "pay");
        vnpParams.put("vnp_TmnCode", properties.getTmnCode());
        vnpParams.put("vnp_Amount", String.valueOf(amountInCents));
        vnpParams.put("vnp_CurrCode", "VND");
        vnpParams.put("vnp_TxnRef", request.getTxnRef());
        vnpParams.put("vnp_OrderInfo", request.getOrderInfo());
        vnpParams.put("vnp_OrderType", properties.getOrderType());
        vnpParams.put("vnp_Locale", properties.getLocale());
        vnpParams.put("vnp_ReturnUrl", properties.getReturnUrl());
        vnpParams.put("vnp_IpAddr", request.getIpAddress());
        vnpParams.put("vnp_CreateDate", createDate);
        vnpParams.put("vnp_ExpireDate", expireDate);

        String hashData = VnPaySignatureHelper.buildHashData(vnpParams);
        String secureHash = VnPaySignatureHelper.hmacSha512(properties.getHashSecret(), hashData);

        return properties.getPayUrl() + "?" + hashData + "&vnp_SecureHash=" + secureHash;
    }

    @Override
    public boolean verifyIpnSignature(Map<String, String> queryParams) {
        properties.validate();

        String receivedHash = queryParams.get("vnp_SecureHash");
        if (receivedHash == null || receivedHash.isBlank()) {
            log.warn("Missing vnp_SecureHash in IPN query parameters");
            return false;
        }

        String hashData = VnPaySignatureHelper.buildHashData(queryParams);
        String computedHash = VnPaySignatureHelper.hmacSha512(properties.getHashSecret(), hashData);

        return VnPaySignatureHelper.constantTimeEquals(computedHash, receivedHash);
    }

    @Override
    public VnPayRefundResponse refund(VnPayRefundRequest request) {
        properties.validate();

        String ip = resolveIp(request.getIpAddress(), "VNPAY refund");
        VnPayProperties.validateLiteralIpAddress(ip, "refund.ipAddress");

        long amountInCents = request.getAmount().multiply(BigDecimal.valueOf(100)).longValueExact();
        String createDate = LocalDateTime.now(GMT7_ZONE).format(DATE_FORMATTER);

        // Canonical string format:
        // RequestId|Version|Command|TmnCode|TransactionType|TxnRef|Amount|TransactionNo|TransactionDate|CreateBy|CreateDate|IpAddr|OrderInfo
        String rawHash = String.join("|",
                request.getRequestId(),
                properties.getVersion(),
                "refund",
                properties.getTmnCode(),
                request.getTransactionType(),
                request.getTxnRef(),
                String.valueOf(amountInCents),
                request.getTransactionNo(),
                request.getTransactionDate(),
                request.getCreateBy(),
                createDate,
                ip,
                request.getOrderInfo()
        );

        String secureHash = VnPaySignatureHelper.hmacSha512(properties.getHashSecret(), rawHash);

        Map<String, Object> body = new HashMap<>();
        body.put("vnp_RequestId", request.getRequestId());
        body.put("vnp_Version", properties.getVersion());
        body.put("vnp_Command", "refund");
        body.put("vnp_TmnCode", properties.getTmnCode());
        body.put("vnp_TransactionType", request.getTransactionType());
        body.put("vnp_TxnRef", request.getTxnRef());
        body.put("vnp_Amount", amountInCents);
        body.put("vnp_TransactionNo", request.getTransactionNo());
        body.put("vnp_TransactionDate", request.getTransactionDate());
        body.put("vnp_CreateBy", request.getCreateBy());
        body.put("vnp_CreateDate", createDate);
        body.put("vnp_IpAddr", ip);
        body.put("vnp_OrderInfo", request.getOrderInfo());
        body.put("vnp_SecureHash", secureHash);

        VnPayRefundResponse response = restClient.post()
                .uri(properties.getApiUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(VnPayRefundResponse.class);

        if (response == null) {
            throw new IllegalStateException("Received null response from VNPAY refund API");
        }

        verifyRefundResponseSignature(response);
        return response;
    }

    @Override
    public VnPayQueryResponse queryTransaction(VnPayQueryRequest request) {
        properties.validate();

        String ip = resolveIp(request.getIpAddress(), "VNPAY query");
        VnPayProperties.validateLiteralIpAddress(ip, "query.ipAddress");

        String createDate = request.getCreateDate();
        if (createDate == null || createDate.isBlank()) {
            createDate = LocalDateTime.now(GMT7_ZONE).format(DATE_FORMATTER);
        }

        // Canonical string format:
        // RequestId|Version|Command|TmnCode|TxnRef|TransactionDate|CreateDate|IpAddr|OrderInfo
        String rawHash = String.join("|",
                request.getRequestId(),
                properties.getVersion(),
                "querydr",
                properties.getTmnCode(),
                request.getTxnRef(),
                request.getTransactionDate(),
                createDate,
                ip,
                request.getOrderInfo()
        );

        String secureHash = VnPaySignatureHelper.hmacSha512(properties.getHashSecret(), rawHash);

        Map<String, Object> body = new HashMap<>();
        body.put("vnp_RequestId", request.getRequestId());
        body.put("vnp_Version", properties.getVersion());
        body.put("vnp_Command", "querydr");
        body.put("vnp_TmnCode", properties.getTmnCode());
        body.put("vnp_TxnRef", request.getTxnRef());
        body.put("vnp_TransactionDate", request.getTransactionDate());
        body.put("vnp_CreateDate", createDate);
        body.put("vnp_IpAddr", ip);
        body.put("vnp_OrderInfo", request.getOrderInfo());
        body.put("vnp_SecureHash", secureHash);

        VnPayQueryResponse response = restClient.post()
                .uri(properties.getApiUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(VnPayQueryResponse.class);

        if (response == null) {
            throw new IllegalStateException("Received null response from VNPAY querydr API");
        }

        verifyQueryResponseSignature(response);
        return response;
    }

    private String resolveIp(String requestIp, String operation) {
        if (requestIp != null && !requestIp.isBlank()) {
            return requestIp;
        }
        if (properties.getIpAddress() != null && !properties.getIpAddress().isBlank()) {
            return properties.getIpAddress();
        }
        throw new IllegalArgumentException("IP address is required for " + operation);
    }

    private void verifyRefundResponseSignature(VnPayRefundResponse response) {
        if (response.getSecureHash() == null || response.getSecureHash().isBlank()) {
            throw new IllegalStateException("Missing vnp_SecureHash in VNPAY refund response");
        }
        String canonicalResponse = VnPaySignatureHelper.buildRefundResponseHashData(
                response.getResponseId(), response.getCommand(), response.getResponseCode(),
                response.getMessage(), response.getTmnCode(), response.getTxnRef(),
                response.getAmount() != null ? String.valueOf(response.getAmount().longValue()) : "",
                response.getBankCode(), response.getPayDate(), response.getTransactionNo(),
                response.getTransactionType(), response.getTransactionStatus(), response.getOrderInfo()
        );
        String computedHash = VnPaySignatureHelper.hmacSha512(properties.getHashSecret(), canonicalResponse);
        if (!VnPaySignatureHelper.constantTimeEquals(computedHash, response.getSecureHash())) {
            log.error("VNPAY refund response signature verification failed");
            throw new IllegalStateException("Invalid secureHash in VNPAY refund response");
        }
    }

    private void verifyQueryResponseSignature(VnPayQueryResponse response) {
        if (response.getSecureHash() == null || response.getSecureHash().isBlank()) {
            throw new IllegalStateException("Missing vnp_SecureHash in VNPAY querydr response");
        }
        String canonicalResponse = VnPaySignatureHelper.buildQueryResponseHashData(
                response.getResponseId(), response.getCommand(), response.getResponseCode(),
                response.getMessage(), response.getTmnCode(), response.getTxnRef(),
                response.getAmount() != null ? String.valueOf(response.getAmount().longValue()) : "",
                response.getBankCode(), response.getPayDate(), response.getTransactionNo(),
                response.getTransactionType(), response.getTransactionStatus(), response.getOrderInfo(),
                response.getPromotionCode(), response.getPromotionAmount()
        );
        String computedHash = VnPaySignatureHelper.hmacSha512(properties.getHashSecret(), canonicalResponse);
        if (!VnPaySignatureHelper.constantTimeEquals(computedHash, response.getSecureHash())) {
            log.error("VNPAY querydr response signature verification failed");
            throw new IllegalStateException("Invalid secureHash in VNPAY querydr response");
        }
    }
}
