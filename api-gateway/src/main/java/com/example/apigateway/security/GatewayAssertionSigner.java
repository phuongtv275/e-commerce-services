package com.example.apigateway.security;

import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Tiện ích ký Asymmetric Cryptographic Assertion JWT sử dụng Private Key RSA-2048.
 *
 * Token này được API Gateway tạo ra độc quyền cho các downstream service đã xác thực,
 * trói buộc đích danh mã định danh service nhận (audience binding - aud claim),
 * và có thời hạn sống cực ngắn (60 giây) để chống tấn công replay.
 */
@Slf4j
@Component
public class GatewayAssertionSigner {

    private final PrivateKey privateKey;

    private static final java.util.Set<String> INSECURE_DEV_KEY_FINGERPRINTS = java.util.Set.of(
            "b013aefb8083e80f6ac4d2188791eda10f245ef4491ec82c5eb3bc03abc9ea4c",
            "45213b0adae47a0087ac71bd47eaab95a3195eda0afa592f73a7a78c2eabbae6"
    );

    @org.springframework.beans.factory.annotation.Autowired
    public GatewayAssertionSigner(
            @Value("${gateway.assertion.private-key:}") String privateKeyPem,
            @org.springframework.beans.factory.annotation.Autowired(required = false) org.springframework.core.env.Environment environment) {
        this.privateKey = parsePrivateKey(privateKeyPem);
        if (this.privateKey == null) {
            throw new IllegalStateException("GATEWAY_ASSERTION_PRIVATE_KEY is missing or invalid. Gateway requires a valid RSA-2048 private key to sign identity assertions.");
        }
        String keyFingerprint = computeSha256Fingerprint(this.privateKey.getEncoded());
        boolean isDevKey = INSECURE_DEV_KEY_FINGERPRINTS.contains(keyFingerprint);
        boolean isProd = environment != null && environment.matchesProfiles("prod", "production");
        if (isDevKey) {
            if (isProd) {
                throw new IllegalStateException("CRITICAL SECURITY ERROR: In production profile, GATEWAY_ASSERTION_PRIVATE_KEY must not use known development or repository keys!");
            }
            log.warn("SECURITY WARNING: Gateway assertion is using a known development RSA private key (SHA-256: {}). Ensure GATEWAY_ASSERTION_PRIVATE_KEY is securely configured in production!", keyFingerprint);
        }
    }

    private static String computeSha256Fingerprint(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) {
            return "";
        }
    }

    public GatewayAssertionSigner(String privateKeyPem) {
        this(privateKeyPem, null);
    }

    /**
     * Ký assertion token ngắn hạn gửi cho downstream microservice.
     *
     * @param userId        Mã định danh người dùng đã xác thực (subject)
     * @param roles         Danh sách vai trò của người dùng
     * @param targetService Tên service đích nhận request (audience)
     * @return Chuỗi JWT đã ký RS256, hoặc null nếu chưa cấu hình private key
     */
    public String signAssertion(String userId, List<String> roles, String targetService) {
        if (privateKey == null) {
            return null;
        }

        Instant now = Instant.now();
        return Jwts.builder()
                .issuer("api-gateway")
                .subject(userId)
                .audience().add(targetService).and()
                .claim("roles", roles != null ? roles : List.of())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(60)))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    private PrivateKey parsePrivateKey(String pem) {
        if (pem == null || pem.isBlank()) {
            return null;
        }

        try {
            String cleanKey = pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                    .replace("-----END RSA PRIVATE KEY-----", "")
                    .replace("\\n", "")
                    .replace("\\r", "")
                    .replaceAll("\\s+", "");

            byte[] keyBytes = Base64.getDecoder().decode(cleanKey);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = keyFactory.generatePrivate(spec);
            if (privateKey instanceof java.security.interfaces.RSAPrivateKey rsaKey) {
                if (rsaKey.getModulus().bitLength() < 2048) {
                    throw new IllegalArgumentException("Gateway assertion RSA key must be at least 2048 bits, but was " + rsaKey.getModulus().bitLength() + " bits");
                }
            }
            return privateKey;
        } catch (Exception e) {
            log.error("Failed to parse RSA Private Key for gateway assertion: {}", e.getMessage());
            return null;
        }
    }
}
