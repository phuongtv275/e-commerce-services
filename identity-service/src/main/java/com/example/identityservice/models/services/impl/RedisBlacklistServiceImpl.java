package com.example.identityservice.models.services.impl;

import com.example.identityservice.exceptions.BadRequestException;
import com.example.identityservice.models.services.RedisBlacklistService;
import com.example.identityservice.security.jwt.JwtUtils;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisBlacklistServiceImpl implements RedisBlacklistService {

    public static final String BLACKLIST_KEY_PREFIX = "blacklist:";
    public static final String REVOKED_VALUE = "revoked";

    private final StringRedisTemplate redisTemplate;
    private final JwtUtils jwtUtils;

    /**
     * Thực hiện thu hồi Access Token và lưu vào Redis Blacklist:
     * Note giải thích logic:
     * 1. Giải mã token để lấy claim "jti" và "exp" (thời gian hết hạn).
     * 2. Tính toán TTL (Time-To-Live): TTL = exp - thời gian hiện tại.
     * 3. Lưu key "blacklist:{jti}" vào Redis với giá trị "revoked".
     * 4. Thiết lập thời gian tự động xóa (Expiration) cho key này trong Redis bằng đúng TTL.
     *    Nếu token đã hết hạn tự nhiên (TTL <= 0), không cần lưu vào Redis vì token đã vô hiệu.
     * 5. Trả về Claims để caller tái sử dụng mà không cần parse JWT lại lần hai.
     *
     * @param token chuỗi Access Token cần thu hồi
     * @return Claims của token
     */
    @Override
    public Claims blacklistToken(String token) {
        log.info("Processing token blacklisting in Redis");
        Claims claims;
        try {
            claims = jwtUtils.extractAllClaims(token);
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Failed to extract claims from token for blacklisting: {}", e.getMessage());
            throw new BadRequestException("Invalid token: " + e.getMessage());
        }

        String jti = claims.getId();
        Date expiration = claims.getExpiration();

        if (jti == null || jti.isBlank()) {
            log.warn("Token does not contain 'jti' claim, cannot blacklist");
            throw new BadRequestException("Invalid token: missing jti claim");
        }

        long now = System.currentTimeMillis();
        long ttlMillis = (expiration != null) ? (expiration.getTime() - now) : 0;

        if (ttlMillis > 0) {
            String redisKey = BLACKLIST_KEY_PREFIX + jti;
            redisTemplate.opsForValue().set(redisKey, REVOKED_VALUE, ttlMillis, TimeUnit.MILLISECONDS);
            log.info("Successfully blacklisted token with jti: [{}] in Redis for {} ms", jti, ttlMillis);
        } else {
            log.info("Token with jti: [{}] is already expired, skip storing to Redis", jti);
        }

        return claims;
    }

    @Override
    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        String redisKey = BLACKLIST_KEY_PREFIX + jti;
        return Boolean.TRUE.equals(redisTemplate.hasKey(redisKey));
    }
}
