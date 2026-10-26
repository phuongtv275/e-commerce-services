package com.example.paymentservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.regex.Pattern;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "vnpay")
public class VnPayProperties {

    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^((25[0-5]|(2[0-4]|1\\d|[1-9]|)\\d)\\.){3}(25[0-5]|(2[0-4]|1\\d|[1-9]|)\\d)$"
    );

    /**
     * Merchant Terminal Code (vnp_TmnCode) - must be supplied via env/secret store
     */
    private String tmnCode;

    /**
     * Merchant Hash Secret Key for HMAC-SHA512 - must be supplied via env/secret store
     */
    private String hashSecret;

    /**
     * Service outbound IP address for merchant API calls (vnp_IpAddr) - bound as vnpay.ip-address
     */
    private String ipAddress;

    /**
     * VNPAY Payment redirect URL (GET)
     */
    private String payUrl = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html";

    /**
     * VNPAY Merchant WebAPI URL (POST for refund/querydr)
     */
    private String apiUrl = "https://sandbox.vnpayment.vn/merchant_webapi/api/transaction";

    /**
     * Return URL after payment completion (vnp_ReturnUrl) - must be non-blank HTTPS
     */
    private String returnUrl;

    /**
     * Public IPN webhook URL - must be non-blank HTTPS accessible by VNPAY sandbox
     */
    private String ipnUrl;

    /**
     * VNPAY API version
     */
    private String version = "2.1.0";

    /**
     * Order category/type (vnp_OrderType)
     */
    private String orderType = "other";

    /**
     * UI display language (vn or en)
     */
    private String locale = "vn";

    /**
     * Payment expiration in minutes (aligned with inventory TTL 15m)
     */
    private int expireMinutes = 15;

    public void validate() {
        if (tmnCode == null || tmnCode.isBlank()) {
            throw new IllegalStateException("vnpay.tmn-code must be configured");
        }
        if (hashSecret == null || hashSecret.isBlank()) {
            throw new IllegalStateException("vnpay.hash-secret must be configured");
        }
        validateLiteralIpAddress(ipAddress, "vnpay.ip-address");
        validateHttpsUrl(payUrl, "vnpay.pay-url");
        validateHttpsUrl(apiUrl, "vnpay.api-url");
        validateHttpsUrl(returnUrl, "vnpay.return-url");
        validateHttpsUrl(ipnUrl, "vnpay.ipn-url");
    }

    public static void validateHttpsUrl(String url, String propertyName) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(propertyName + " must be non-blank");
        }
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalStateException(propertyName + " must use HTTPS scheme, got: " + url);
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalStateException(propertyName + " must have a valid host, got: " + url);
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(propertyName + " is not a valid URL: " + url, e);
        }
    }

    public static void validateLiteralIpAddress(String ip, String propertyName) {
        if (ip == null || ip.isBlank()) {
            throw new IllegalStateException(propertyName + " must be configured as a valid literal IP address");
        }
        String trimmed = ip.trim();
        if ("0.0.0.0".equals(trimmed) || "127.0.0.1".equals(trimmed) || "::1".equals(trimmed) || "localhost".equalsIgnoreCase(trimmed)) {
            throw new IllegalStateException(propertyName + " must not be loopback or wildcard (got: " + ip + ")");
        }
        boolean isIpv4 = IPV4_PATTERN.matcher(trimmed).matches();
        boolean isIpv6 = trimmed.contains(":");
        if (!isIpv4 && !isIpv6) {
            throw new IllegalStateException(propertyName + " must be a literal IPv4 or IPv6 address (got: " + ip + ")");
        }
        try {
            InetAddress address = InetAddress.getByName(trimmed);
            if (address.isLoopbackAddress() || address.isAnyLocalAddress()) {
                throw new IllegalStateException(propertyName + " must not be loopback or any-local address (got: " + ip + ")");
            }
        } catch (Exception e) {
            throw new IllegalStateException(propertyName + " is not a valid IP address: " + ip, e);
        }
    }
}
