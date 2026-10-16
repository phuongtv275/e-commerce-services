package com.example.identityservice.security.jwt;

import com.example.identityservice.models.entities.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
public class JwtUtils {

    @Value("${app.jwt.secret}")
    private String secretKey;

    @Value("${app.jwt.access-token-expiration:900000}")
    private Long accessTokenExpiration;

    private SecretKey signKey;

    @PostConstruct
    public void init() {
        byte[] bytes = Decoders.BASE64.decode(secretKey);
        this.signKey = Keys.hmacShaKeyFor(bytes);
    }

    public SecretKey getSignKey() {
        if (this.signKey == null) {
            init();
        }
        return this.signKey;
    }

    /**
     * Tạo Access Token (JWT) ngắn hạn chứa thông tin người dùng, vai trò (roles)
     * và claim định danh duy nhất jti (JWT ID) để hỗ trợ Blacklist/Revocation khi Logout.
     */
    public String generateAccessToken(User user) {
        List<String> roleNames = user.getRoles() != null
                ? user.getRoles().stream()
                    .map(role -> role.getRoleName().name())
                    .toList()
                : List.of();

        String jti = UUID.randomUUID().toString();
        String subject = user.getId() != null ? user.getId().toString() : user.getEmail();

        return Jwts.builder()
                .subject(subject)
                .claim("email", user.getEmail())
                .claim("username", user.getEmail())
                .claim("roles", roleNames)
                .id(jti)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessTokenExpiration))
                .signWith(getSignKey())
                .compact();
    }

    public String generateToken(User user) {
        return generateAccessToken(user);
    }

    /**
     * Giải mã toàn bộ Claims từ chuỗi JWT.
     * Cho phép lấy claims ngay cả khi token đã hết hạn tự nhiên (ExpiredJwtException).
     */
    public Claims extractAllClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(getSignKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            log.debug("Token has expired, returning claims from ExpiredJwtException: {}", e.getMessage());
            return e.getClaims();
        }
    }

    public String extractJti(String token) {
        return extractAllClaims(token).getId();
    }

    public Date extractExpiration(String token) {
        return extractAllClaims(token).getExpiration();
    }

    public String extractUsername(String token) {
        Claims claims = extractAllClaims(token);
        String email = claims.get("email", String.class);
        return email != null ? email : claims.getSubject();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(getSignKey())
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
