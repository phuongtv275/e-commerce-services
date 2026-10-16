package com.example.identityservice.models.services.impl;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.exception.BusinessException;
import com.example.identityservice.exceptions.NotFoundException;
import com.example.identityservice.exceptions.TokenRefreshException;
import com.example.identityservice.models.dto.res.TokenResponseDTO;
import com.example.identityservice.models.entities.RefreshToken;
import com.example.identityservice.models.entities.User;
import com.example.identityservice.models.repositories.RefreshTokenRepository;
import com.example.identityservice.models.repositories.UserRepository;
import com.example.identityservice.models.services.RefreshTokenService;
import com.example.identityservice.security.jwt.JwtUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenServiceImpl implements RefreshTokenService {

    @Value("${app.jwt.refresh-token-expiration:604800000}")
    private Long refreshTokenDurationMs;

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtUtils jwtUtils;

    public static String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    @Override
    @Transactional
    public RefreshToken createRefreshToken(User user) {
        return createRefreshToken(user, UUID.randomUUID());
    }

    @Transactional
    public RefreshToken createRefreshToken(User user, UUID familyId) {
        log.info("Creating refresh token for user: {}", user.getUsername());

        String rawToken = UUID.randomUUID().toString();
        String tokenHash = hashToken(rawToken);

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(tokenHash)
                .familyId(familyId)
                .expiresAt(Instant.now().plusMillis(refreshTokenDurationMs))
                .revoked(false)
                .build();
        refreshToken.setRawToken(rawToken);

        RefreshToken saved = refreshTokenRepository.save(refreshToken);
        saved.setRawToken(rawToken);
        log.info("Successfully created refresh token id: {} with familyId: {} for user: {}", saved.getId(), familyId, user.getUsername());
        return saved;
    }

    @Override
    @Transactional
    public RefreshToken createRefreshToken(UUID userId) {
        log.info("Creating refresh token for userId: {}", userId);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found with id: " + userId));
        return createRefreshToken(user);
    }

    @Override
    @Transactional(noRollbackFor = TokenRefreshException.class)
    public RefreshToken verifyExpiration(RefreshToken token) {
        if (token.getExpiresAt().isBefore(Instant.now())) {
            log.warn("Refresh token [{}] expired at {}", token.getId(), token.getExpiresAt());
            token.setRevoked(true);
            refreshTokenRepository.save(token);
            throw new TokenRefreshException(token.getToken(), "Invalid or expired refresh token. Please make a new signin request");
        }
        return token;
    }

    @Override
    public Optional<RefreshToken> findByToken(String token) {
        String tokenHash = hashToken(token);
        return refreshTokenRepository.findByTokenHash(tokenHash);
    }

    @Override
    @Transactional
    public int deleteByUserId(UUID userId) {
        log.info("Deleting refresh tokens for userId: {}", userId);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found with id: " + userId));
        return deleteByUser(user);
    }

    @Override
    @Transactional
    public int deleteByUser(User user) {
        if (user == null) {
            return 0;
        }
        log.info("Deleting refresh tokens for user: [{}] (id: {})", user.getUsername(), user.getId());
        return refreshTokenRepository.deleteByUser(user);
    }

    @Override
    @Transactional(noRollbackFor = {TokenRefreshException.class, BusinessException.class, AccessDeniedException.class})
    public TokenResponseDTO refreshToken(String requestToken) {
        log.info("Processing token rotation for refresh token");
        if (requestToken == null || requestToken.isBlank()) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED, "Mã refresh token không được để trống");
        }

        String tokenHash = hashToken(requestToken);

        // 1. Tìm RefreshToken trong DB với PESSIMISTIC_WRITE lock
        RefreshToken token = refreshTokenRepository.findByTokenHashWithLock(tokenHash)
                .orElseGet(() -> refreshTokenRepository.findByTokenWithLock(requestToken).orElse(null));

        if (token == null) {
            log.warn("Token rotation failed: Refresh token not found");
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED, "Phiên đăng nhập không hợp lệ hoặc đã hết hạn");
        }

        // 2. Token Reuse Detection
        if (token.isRevoked()) {
            log.warn("SECURITY ALERT: Refresh token reuse detected for token id: {}, familyId: {}. Revoking family!",
                    token.getId(), token.getFamilyId());
            refreshTokenRepository.revokeEntireFamily(token.getFamilyId());
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "Phát hiện hành vi sử dụng lại mã phiên. Toàn bộ phiên làm việc liên quan đã bị hủy vì lý do an toàn.");
        }

        // 3. Kiểm tra hết hạn
        if (token.getExpiresAt().isBefore(Instant.now())) {
            log.warn("Refresh token expired at: {}", token.getExpiresAt());
            token.setRevoked(true);
            refreshTokenRepository.save(token);
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED, "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.");
        }

        // 4. Token Rotation hợp lệ: Đánh dấu token cũ đã bị thu hồi
        token.setRevoked(true);
        refreshTokenRepository.save(token);

        User user = token.getUser();

        // 5. Sinh Refresh Token mới kế thừa cùng familyId
        RefreshToken newRefreshToken = createRefreshToken(user, token.getFamilyId());

        // 6. Sinh Access Token mới
        String newAccessToken = jwtUtils.generateAccessToken(user);

        List<String> roles = user.getRoles() != null
                ? user.getRoles().stream()
                    .map(role -> role.getRoleName().name())
                    .toList()
                : List.of();

        log.info("Token rotation completed successfully for user: {}. New refresh token family: {}",
                user.getUsername(), token.getFamilyId());

        return new TokenResponseDTO(
                newAccessToken,
                newRefreshToken.getToken(),
                roles
        );
    }
}
