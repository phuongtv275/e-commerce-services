package com.example.paymentservice.provider;

import com.example.paymentservice.config.VnPayProperties;
import com.example.paymentservice.provider.vnpay.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VnpayClientTests {

    private VnPayProperties properties;
    private VnpayClientImpl vnpayClient;

    @BeforeEach
    void setUp() {
        properties = new VnPayProperties();
        properties.setTmnCode("TESTTMN01");
        properties.setHashSecret("SECRETKEY12345678901234567890123456789012");
        properties.setIpAddress("103.1.2.3");
        properties.setPayUrl("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");
        properties.setApiUrl("https://sandbox.vnpayment.vn/merchant_webapi/api/transaction");
        properties.setReturnUrl("https://merchant.example.com/checkout/return");
        properties.setIpnUrl("https://merchant.example.com/api/v1/payments/vnpay/ipn");
        properties.setVersion("2.1.0");
        properties.setLocale("vn");
        properties.setOrderType("other");
        properties.setExpireMinutes(15);

        vnpayClient = new VnpayClientImpl(properties, RestClient.builder().build());
    }

    @Test
    @DisplayName("Positive: createPaymentUrl builds signed URL with amount in cents and vnp_SecureHash")
    void testCreatePaymentUrlSuccess() {
        VnPayPaymentUrlRequest request = VnPayPaymentUrlRequest.builder()
                .txnRef("TXN_123456")
                .amount(new BigDecimal("50000")) // 50,000 VND -> 5,000,000 cents
                .orderInfo("Đơn hàng #123456")
                .ipAddress("203.162.0.1")
                .createDate("20261025180000")
                .build();

        String url = vnpayClient.createPaymentUrl(request);

        assertNotNull(url);
        assertTrue(url.startsWith("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?"));
        assertTrue(url.contains("vnp_Amount=5000000"));
        assertTrue(url.contains("vnp_TmnCode=TESTTMN01"));
        assertTrue(url.contains("vnp_TxnRef=TXN_123456"));
        assertTrue(url.contains("vnp_CurrCode=VND"));
        assertTrue(url.contains("vnp_IpAddr=203.162.0.1"));
        assertTrue(url.contains("vnp_ExpireDate=20261025181500"));
        assertTrue(url.contains("&vnp_SecureHash="));

        // Verify the signature on the generated URL
        String queryString = url.substring(url.indexOf('?') + 1);
        String[] params = queryString.split("&");
        Map<String, String> parsed = new HashMap<>();
        for (String param : params) {
            int idx = param.indexOf('=');
            String key = URLDecoder.decode(param.substring(0, idx), StandardCharsets.UTF_8);
            String val = URLDecoder.decode(param.substring(idx + 1), StandardCharsets.UTF_8);
            parsed.put(key, val);
        }

        assertTrue(vnpayClient.verifyIpnSignature(parsed), "The signature on the generated URL should be verifiable");
    }

    @Test
    @DisplayName("Negative: createPaymentUrl throws IllegalArgumentException when client IP is missing")
    void testCreatePaymentUrlMissingIpThrows() {
        VnPayPaymentUrlRequest request = VnPayPaymentUrlRequest.builder()
                .txnRef("TXN_123456")
                .amount(new BigDecimal("100000"))
                .orderInfo("Test order")
                .ipAddress("") // empty IP
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> vnpayClient.createPaymentUrl(request));
        assertTrue(ex.getMessage().contains("Client IP address is required"));
    }

    @Test
    @DisplayName("Negative: createPaymentUrl rejects non-HTTPS or missing callback URL")
    void testCreatePaymentUrlRejectsHttpCallback() {
        properties.setReturnUrl("http://insecure.example.com/return"); // HTTP instead of HTTPS

        VnPayPaymentUrlRequest request = VnPayPaymentUrlRequest.builder()
                .txnRef("TXN_123456")
                .amount(new BigDecimal("100000"))
                .orderInfo("Test order")
                .ipAddress("203.162.0.1")
                .build();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> vnpayClient.createPaymentUrl(request));
        assertTrue(ex.getMessage().contains("must use HTTPS"));
    }

    @Test
    @DisplayName("Negative: validate rejects loopback or wildcard outbound IP address")
    void testValidateRejectsLoopbackIp() {
        properties.setIpAddress("127.0.0.1");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> properties.validate());
        assertTrue(ex.getMessage().contains("must not be loopback"));
    }

    @Test
    @DisplayName("Positive & Negative: verifyIpnSignature accepts valid HMAC and rejects tampered data")
    void testVerifyIpnSignature() {
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("vnp_Amount", "10000000");
        queryParams.put("vnp_BankCode", "NCB");
        queryParams.put("vnp_OrderInfo", "Thanh toan don hang #100");
        queryParams.put("vnp_PayDate", "20261025181000");
        queryParams.put("vnp_ResponseCode", "00");
        queryParams.put("vnp_TmnCode", "TESTTMN01");
        queryParams.put("vnp_TransactionNo", "14000123");
        queryParams.put("vnp_TransactionStatus", "00");
        queryParams.put("vnp_TxnRef", "TXN_ORD_100");

        String hashData = VnPaySignatureHelper.buildHashData(queryParams);
        String correctHash = VnPaySignatureHelper.hmacSha512(properties.getHashSecret(), hashData);
        queryParams.put("vnp_SecureHash", correctHash);

        // Positive
        assertTrue(vnpayClient.verifyIpnSignature(queryParams));

        // Negative: Tampered amount
        Map<String, String> tampered = new HashMap<>(queryParams);
        tampered.put("vnp_Amount", "99999999");
        assertFalse(vnpayClient.verifyIpnSignature(tampered));

        // Negative: Missing vnp_SecureHash
        Map<String, String> missingHash = new HashMap<>(queryParams);
        missingHash.remove("vnp_SecureHash");
        assertFalse(vnpayClient.verifyIpnSignature(missingHash));
    }
}
