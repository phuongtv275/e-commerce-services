package com.example.identityservice.models.services.impl;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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

    /**
     * Tạo mới Refresh Token dạng UUID và lưu thông tin hạn sử dụng xuống cơ sở dữ liệu.
     * Note logic: Tìm kiếm User, xóa token cũ (nếu có) để đảm bảo mỗi phiên đăng nhập mới
     * sẽ sở hữu token duy nhất, tránh token rác tích tụ trong database.
     *
     * @param userId mã định danh của User cần cấp token
     * @return đối tượng RefreshToken đã lưu
     */
    @Override
    @Transactional
    public RefreshToken createRefreshToken(User user) {
        log.info("Creating refresh token for user: {}", user.getUsername());

        // Dọn dẹp token cũ của user (nếu có) bằng atomic delete để tránh NonUniqueResultException
        refreshTokenRepository.deleteByUser(user);

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .token(UUID.randomUUID().toString())
                .familyId(UUID.randomUUID())
                .expiryDate(Instant.now().plusMillis(refreshTokenDurationMs))
                .build();

        RefreshToken saved = refreshTokenRepository.save(refreshToken);
        log.info("Successfully created refresh token id: {} for user: {}", saved.getId(), user.getUsername());
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

    /**
     * Xác thực xem Refresh Token đã hết hạn hay chưa.
     * Note logic: So sánh expiryDate với Instant.now().
     * Nếu đã hết hạn, xóa bản ghi khỏi DB để dọn dẹp và ném TokenRefreshException.
     * Cấu hình noRollbackFor để đảm bảo lệnh delete vẫn được commit xuống DB khi ném exception.
     *
     * @param token RefreshToken cần kiểm tra
     * @return token hợp lệ nếu chưa hết hạn
     */
    @Override
    @Transactional(noRollbackFor = TokenRefreshException.class)
    public RefreshToken verifyExpiration(RefreshToken token) {
        if (token.getExpiryDate().compareTo(Instant.now()) < 0) {
            log.warn("Refresh token [{}] expired at {}", token.getToken(), token.getExpiryDate());
            refreshTokenRepository.delete(token);
            refreshTokenRepository.flush();
            throw new TokenRefreshException(token.getToken(), "Invalid or expired refresh token. Please make a new signin request");
        }
        return token;
    }

    @Override
    public Optional<RefreshToken> findByToken(String token) {
        return refreshTokenRepository.findByToken(token);
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

    /**
     * Xử lý cấp lại Token mới (Token Rotation):
     * Note giải thích logic:
     * 1. Tìm RefreshToken trong DB bằng chuỗi requestToken với PESSIMISTIC_WRITE lock (SELECT FOR UPDATE)
     *    để ngăn chặn race condition/replay attack khi 2 request cùng gửi một token đồng thời.
     * 2. Kiểm tra token đã hết hạn chưa qua verifyExpiration. Nếu hết hạn, phương thức sẽ xóa token và ném TokenRefreshException (403).
     *    Nhờ cấu hình noRollbackFor = TokenRefreshException.class, lệnh xóa token hết hạn sẽ được commit thành công xuống DB.
     * 3. (QUAN TRỌNG - Rotation): Xóa bản ghi Refresh Token cũ khỏi DB để ngăn chặn tấn công chiếm hữu / replay phiên làm việc.
     * 4. Lấy User từ token cũ, gọi JwtUtils để tạo Access Token mới.
     * 5. Gọi hàm createRefreshToken(user) để tạo Refresh Token hoàn toàn mới và lưu vào DB.
     * 6. Trả về cặp Token mới (TokenResponseDTO) cho Client.
     *
     * @param requestToken chuỗi refresh token do client gửi lên
     * @return TokenResponseDTO chứa accessToken mới và refreshToken mới
     */
    @Override
    @Transactional(noRollbackFor = TokenRefreshException.class)
    public TokenResponseDTO refreshToken(String requestToken) {
        log.info("Processing token rotation for refresh token");

        // 1. Tìm RefreshToken trong DB với PESSIMISTIC_WRITE lock để ngăn chặn 2 request đồng thời
        RefreshToken token = refreshTokenRepository.findByTokenWithLock(requestToken)
                .orElseThrow(() -> {
                    log.warn("Token rotation failed: Refresh token not found or already revoked");
                    return new TokenRefreshException(requestToken, "Invalid or expired refresh token. Please make a new signin request");
                });

        // 2. Kiểm tra token đã hết hạn chưa. Nếu hết hạn, verifyExpiration xóa token và ném TokenRefreshException.
        verifyExpiration(token);

        User user = token.getUser();

        // 3. (QUAN TRỌNG - Rotation): Xóa bản ghi Refresh Token cũ khỏi DB
        refreshTokenRepository.delete(token);
        refreshTokenRepository.flush();
        log.info("Old refresh token id [{}] deleted for user: {}", token.getId(), user.getUsername());

        // 4. Lấy User từ token cũ, gọi JwtUtils để tạo Access Token mới
        String newAccessToken = jwtUtils.generateAccessToken(user);

        // 5. Gọi hàm createRefreshToken để tạo Refresh Token hoàn toàn mới
        RefreshToken newRefreshToken = createRefreshToken(user);

        List<String> roles = user.getRoles() != null
                ? user.getRoles().stream()
                    .map(role -> role.getRoleName().name())
                    .toList()
                : List.of();

        log.info("Token rotation completed successfully for user: {}. New refresh token id: {}",
                user.getUsername(), newRefreshToken.getId());

        // 6. Trả về cặp Token mới cho Client
        return new TokenResponseDTO(
                newAccessToken,
                newRefreshToken.getToken(),
                roles
        );
    }
}
