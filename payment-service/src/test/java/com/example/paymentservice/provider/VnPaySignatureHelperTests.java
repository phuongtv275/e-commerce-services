package com.example.paymentservice.provider;

import com.example.paymentservice.provider.vnpay.VnPaySignatureHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class VnPaySignatureHelperTests {

    private static final String TEST_SECRET = "MYSECRETKEY1234567890123456789012";

    @Test
    @DisplayName("Positive: HMAC-SHA512 produces deterministic lowercase hex output")
    void testHmacSha512Deterministic() {
        String data = "vnp_Amount=1000000&vnp_Command=pay&vnp_TmnCode=TESTTMN";
        String hash1 = VnPaySignatureHelper.hmacSha512(TEST_SECRET, data);
        String hash2 = VnPaySignatureHelper.hmacSha512(TEST_SECRET, data);

        assertNotNull(hash1);
        assertEquals(128, hash1.length(), "HMAC-SHA512 hex string must be 128 characters");
        assertEquals(hash1, hash2);
        assertEquals(hash1.toLowerCase(), hash1, "Hash must be lowercase hex");
    }

    @Test
    @DisplayName("Positive: Canonical parameters sort alphabetically and exclude secure hash fields")
    void testBuildHashDataSortingAndExclusions() {
        Map<String, String> params = new HashMap<>();
        params.put("vnp_TxnRef", "TXN001");
        params.put("vnp_Amount", "5000000");
        params.put("vnp_Command", "pay");
        params.put("vnp_SecureHash", "SHOULD_BE_EXCLUDED");
        params.put("vnp_SecureHashType", "SHA512");
        params.put("empty_param", "");
        params.put("null_param", null);

        String hashData = VnPaySignatureHelper.buildHashData(params);

        assertEquals("vnp_Amount=5000000&vnp_Command=pay&vnp_TxnRef=TXN001", hashData);
        assertFalse(hashData.contains("SHOULD_BE_EXCLUDED"));
        assertFalse(hashData.contains("SHA512"));
        assertFalse(hashData.contains("empty_param"));
    }

    @Test
    @DisplayName("Positive: Canonical parameters correctly encode Unicode/Vietnamese with UTF-8")
    void testBuildHashDataUnicodeVietnamese() {
        Map<String, String> params = new TreeMap<>();
        params.put("vnp_OrderInfo", "Thanh toán đơn hàng #99 (Áo & Quần)");
        params.put("vnp_TmnCode", "TESTCODE");

        String hashData = VnPaySignatureHelper.buildHashData(params);

        assertTrue(hashData.contains("vnp_OrderInfo="));
        assertTrue(hashData.startsWith("vnp_OrderInfo="));
        assertTrue(hashData.endsWith("&vnp_TmnCode=TESTCODE"));

        // Verify it can be decoded back with UTF-8
        String[] parts = hashData.split("&");
        String encodedOrderInfo = parts[0].substring("vnp_OrderInfo=".length());
        String decoded = URLDecoder.decode(encodedOrderInfo, StandardCharsets.UTF_8);
        assertEquals("Thanh toán đơn hàng #99 (Áo & Quần)", decoded);
    }

    @Test
    @DisplayName("Positive & Negative: constantTimeEquals handles equality and timing attack resistance")
    void testConstantTimeEquals() {
        String hash = VnPaySignatureHelper.hmacSha512(TEST_SECRET, "test-data");
        String sameHashUppercase = hash.toUpperCase();
        String differentHash = hash.substring(0, 127) + "0";

        assertTrue(VnPaySignatureHelper.constantTimeEquals(hash, hash));
        assertTrue(VnPaySignatureHelper.constantTimeEquals(hash, sameHashUppercase), "Should compare hex case-insensitively");
        assertFalse(VnPaySignatureHelper.constantTimeEquals(hash, differentHash));
        assertFalse(VnPaySignatureHelper.constantTimeEquals(hash, null));
        assertFalse(VnPaySignatureHelper.constantTimeEquals(null, hash));
    }

    @Test
    @DisplayName("Positive: buildRefundResponseHashData builds exact 13-field pipe-delimited canonical string ending at OrderInfo")
    void testBuildRefundResponseHashData() {
        String canonical = VnPaySignatureHelper.buildRefundResponseHashData(
                "RESP01", "refund", "00", "Success", "TESTTMN",
                "TXN100", "100000", "NCB", "20261025180000",
                "VNP999", "02", "00", "Hoan tien"
        );

        assertEquals("RESP01|refund|00|Success|TESTTMN|TXN100|100000|NCB|20261025180000|VNP999|02|00|Hoan tien", canonical);
        assertEquals(13, canonical.split("\\|", -1).length);
    }

    @Test
    @DisplayName("Positive: buildQueryResponseHashData always includes 15 pipe-delimited fields, preserving tail when optional fields absent")
    void testBuildQueryResponseHashDataAbsentOptionalFields() {
        String canonical = VnPaySignatureHelper.buildQueryResponseHashData(
                "RESP01", "querydr", "00", "Success", "TESTTMN",
                "TXN100", "100000", "NCB", "20261025180000",
                "VNP999", "01", "00", "Truy van",
                null, null // absent promotionCode and promotionAmount
        );

        assertEquals("RESP01|querydr|00|Success|TESTTMN|TXN100|100000|NCB|20261025180000|VNP999|01|00|Truy van||", canonical);
        assertEquals(15, canonical.split("\\|", -1).length);
    }

    @Test
    @DisplayName("Positive: buildQueryResponseHashData includes populated promotionCode and promotionAmount in fixed positions")
    void testBuildQueryResponseHashDataPopulatedOptionalFields() {
        String canonical = VnPaySignatureHelper.buildQueryResponseHashData(
                "RESP01", "querydr", "00", "Success", "TESTTMN",
                "TXN100", "100000", "NCB", "20261025180000",
                "VNP999", "01", "00", "Truy van",
                "PROMO2026", "20000"
        );

        assertEquals("RESP01|querydr|00|Success|TESTTMN|TXN100|100000|NCB|20261025180000|VNP999|01|00|Truy van|PROMO2026|20000", canonical);
        assertEquals(15, canonical.split("\\|", -1).length);
    }
}
