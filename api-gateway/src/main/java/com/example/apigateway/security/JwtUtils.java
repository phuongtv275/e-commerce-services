package com.example.apigateway.security;

import com.example.apigateway.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;

/**
 * Utility class chịu trách nhiệm parse và validate JWT token tại Gateway layer.
 *
 * Lưu ý: Class này chỉ cần VERIFY (xác minh chữ ký + thời hạn),
 * không cần generate token — đó là việc của identity-service.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtUtils {

    private final JwtProperties jwtProperties;

    @jakarta.annotation.PostConstruct
    public void validateSecretOnStartup() {
        getSignKey();
    }

    /**
     * Tạo SecretKey từ cấu hình (hỗ trợ cả Base64 và UTF-8 raw string >= 256 bits).
     * Phải dùng cùng thuật toán (HMAC-SHA256) với identity-service.
     */
    private SecretKey getSignKey() {
        String secret = jwtProperties.getSecretKey();
        if (secret == null || secret.isBlank() || "${JWT_SECRET}".equals(secret)) {
            // Nếu biến môi trường JWT_SECRET chưa được nạp, fallback về secret mặc định của hệ thống
            log.warn("SECURITY WARNING: JWT_SECRET environment variable is missing or placeholder. Falling back to default development secret. DO NOT USE IN PRODUCTION!");
            secret = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";
        } else if ("404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970".equals(secret.trim())) {
            log.warn("SECURITY WARNING: Default development JWT secret key is detected. Ensure JWT_SECRET is rotated for production environments!");
        }

        secret = secret.trim();
        byte[] keyBytes;

        if (secret.startsWith("raw:")) {
            keyBytes = secret.substring(4).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } else if (secret.startsWith("base64:")) {
            String b64 = secret.substring(7).trim();
            keyBytes = (b64.contains("-") || b64.contains("_")) ? Decoders.BASE64URL.decode(b64) : Decoders.BASE64.decode(b64);
        } else if ("raw".equalsIgnoreCase(jwtProperties.getSecretFormat())) {
            keyBytes = secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } else {
            // Mode "base64" (mặc định): hỗ trợ cả standard Base64 và Base64URL
            keyBytes = (secret.contains("-") || secret.contains("_"))
                    ? Decoders.BASE64URL.decode(secret)
                    : Decoders.BASE64.decode(secret);
        }

        if (keyBytes.length < 32) {
            throw new IllegalArgumentException("JWT secret key must provide at least 256 bits (32 bytes) of key material");
        }

        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Parse JWT và trả về Claims nếu token hợp lệ.
     *
     * Ném các exception tương ứng nếu token không hợp lệ:
     * - {@link ExpiredJwtException}   : token hết hạn
     * - {@link JwtException}          : chữ ký sai, token bị tamper hoặc sai format
     * - {@link IllegalArgumentException}: token null/rỗng
     *
     * @param token chuỗi JWT (không bao gồm tiền tố "Bearer ")
     * @return Claims chứa thông tin payload của token
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSignKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Trích xuất claim jti (JWT ID) từ token.
     *
     * @param token chuỗi JWT
     * @return jti của token
     */
    public String extractJti(String token) {
        Claims claims = parseToken(token);
        return claims != null ? claims.getId() : null;
    }

    /**
     * Kiểm tra nhanh token có hợp lệ không (không throw exception ra ngoài).
     * Dùng để log thêm thông tin, còn filter vẫn handle exception trực tiếp.
     */
    public boolean isTokenValid(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception ex) {
            log.debug("Token validation failed: {}", ex.getMessage());
            return false;
        }
    }
}
