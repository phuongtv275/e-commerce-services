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

    /**
     * Tạo SecretKey từ chuỗi hex trong config.
     * Phải dùng cùng thuật toán (HMAC-SHA256) với identity-service.
     */
    private SecretKey getSignKey() {
        byte[] keyBytes = Decoders.BASE64.decode(jwtProperties.getSecretKey());
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
