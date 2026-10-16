package com.example.identityservice.models.services.impl;

import com.example.identityservice.exceptions.BadRequestException;
import com.example.identityservice.exceptions.NotFoundException;
import com.example.identityservice.models.constants.RoleName;
import com.example.identityservice.models.dto.req.LoginReq;
import com.example.identityservice.models.dto.req.RefreshTokenReq;
import com.example.identityservice.models.dto.req.RegisterReq;
import com.example.identityservice.models.dto.res.JwtRes;
import com.example.identityservice.models.dto.res.TokenResponseDTO;
import com.example.identityservice.models.entities.RefreshToken;
import com.example.identityservice.models.entities.Role;
import com.example.identityservice.models.entities.User;
import com.example.identityservice.models.repositories.RoleRepository;
import com.example.identityservice.models.repositories.UserRepository;
import com.example.identityservice.models.services.AuthService;
import com.example.identityservice.models.services.RedisBlacklistService;
import com.example.identityservice.models.services.RefreshTokenService;
import com.example.identityservice.security.jwt.JwtUtils;
import com.example.identityservice.security.principal.MyUserDetails;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final AuthenticationManager authenticationManager;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final RefreshTokenService refreshTokenService;
    private final RedisBlacklistService redisBlacklistService;

    @Override
    public void register(RegisterReq req) {
        log.info("Processing registration for username: {}", req.username());
        Set<Role> roles = new HashSet<>();
        roles.add(roleRepository.findByRoleName(RoleName.ROLE_CUSTOMER).orElseThrow(
                () -> new NotFoundException("Role not found")
        ));

        User user = User.builder()
                .fullName(req.fullName())
                .username(req.username())
                .password(passwordEncoder.encode(req.password()))
                .roles(roles)
                .build();
        userRepository.save(user);
        log.info("Successfully registered user: {}", user.getUsername());
    }

    /**
     * Nâng cấp luồng Đăng nhập: Cấp phát Access Token và Refresh Token.
     * Note giải thích logic:
     * 1. Xác thực username/password qua AuthenticationManager. Nếu sai mật khẩu, ném BadRequestException.
     * 2. Lấy thông tin User từ UserDetails trong SecurityContext.
     * 3. Sinh Access Token (JWT thời hạn ngắn - ví dụ 15 phút) dùng để xác thực nhanh ở API Gateway/Services.
     * 4. Sinh Refresh Token (UUID ngẫu nhiên lưu trong DB PostgreSQL, thời hạn dài - ví dụ 7 ngày).
     * 5. Trả về DTO JwtRes chứa đầy đủ accessToken, refreshToken, tokenType ("Bearer") và danh sách roles.
     *
     * @param req thông tin đăng nhập từ client
     * @return DTO JwtRes chứa cả 2 loại token
     */
    @Override
    public JwtRes login(LoginReq req) {
        log.info("Processing login request for username: {}", req.username());
        Authentication authentication;

        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.username(), req.password())
            );
        } catch (AuthenticationException e) {
            log.warn("Authentication failed for username [{}]: {}", req.username(), e.getMessage());
            throw new BadRequestException("Incorrect username or password");
        }

        SecurityContextHolder.getContext().setAuthentication(authentication);
        MyUserDetails userDetails = (MyUserDetails) authentication.getPrincipal();
        User user = userDetails.getUser();

        // 1. Tạo Access Token (JWT)
        String accessToken = jwtUtils.generateAccessToken(user);

        // 2. Tạo Refresh Token (UUID lưu vào PostgreSQL - truyền trực tiếp entity để tránh redundant query)
        RefreshToken refreshToken = refreshTokenService.createRefreshToken(user);

        List<String> roles = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        log.info("User [{}] successfully logged in. Issued access token and refresh token id: {}",
                user.getUsername(), refreshToken.getId());

        return new JwtRes(
                accessToken,
                refreshToken.getToken(),
                "Bearer",
                roles
        );
    }

    @Override
    public TokenResponseDTO refreshToken(RefreshTokenReq req) {
        log.info("Processing refreshToken in AuthService");
        return refreshTokenService.refreshToken(req.refreshToken());
    }

    /**
     * Xử lý Đăng xuất (Logout) và ghi Blacklist vào Redis:
     * Note giải thích logic:
     * 1. Chuẩn hóa token (loại bỏ tiền tố Bearer nếu có).
     * 2. Giải mã token để lấy claim "jti" và "exp" (thời gian hết hạn), tính TTL và lưu vào Redis Blacklist.
     * 3. Xóa toàn bộ Refresh Token của User trong PostgreSQL để vô hiệu hóa hoàn toàn phiên làm việc.
     *
     * @param token chuỗi Access Token cần thu hồi
     */
    @Override
    @Transactional
    public void logout(String token) {
        log.info("Processing logout request");
        if (token == null || token.isBlank()) {
            throw new BadRequestException("Token must not be blank for logout");
        }

        String cleanToken = token.startsWith("Bearer ") ? token.substring(7).trim() : token.trim();

        // 1. Lưu jti vào Redis Blacklist với TTL tương ứng (và trích xuất Claims)
        Claims claims = redisBlacklistService.blacklistToken(cleanToken);

        // 2. Thu hồi Refresh Token trong DB PostgreSQL của user (nếu tìm thấy user)
        String username = claims != null ? claims.getSubject() : null;
        if (username != null && !username.isBlank()) {
            userRepository.findByUsername(username).ifPresent(user -> {
                refreshTokenService.deleteByUser(user);
                log.info("Revoked refresh tokens in database for user: [{}]", username);
            });
        }

        log.info("Logout successfully completed");
    }
}
