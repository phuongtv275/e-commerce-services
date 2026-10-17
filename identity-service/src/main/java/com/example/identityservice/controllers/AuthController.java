package com.example.identityservice.controllers;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.response.ApiResponse;
import com.example.identityservice.models.dto.req.*;
import com.example.identityservice.models.dto.res.JwtRes;
import com.example.identityservice.models.dto.res.TokenResponseDTO;
import com.example.identityservice.models.services.AuthService;
import com.example.identityservice.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping({"/api/v1/auth", "/api/auth"})
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<Void>> register(@RequestBody @Valid RegisterReq req) {
        log.info("Received register request for email: {}", req.email());
        authService.register(req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Đăng ký tài khoản thành công. Vui lòng kiểm tra email để kích hoạt tài khoản.", null));
    }

    @PostMapping("/verify-email")
    public ResponseEntity<ApiResponse<Void>> verifyEmail(@RequestBody @Valid VerifyEmailReq req) {
        log.info("Received verify email request");
        authService.verifyEmail(req);
        return ResponseEntity.ok(ApiResponse.success("Xác minh email thành công. Tài khoản đã được kích hoạt.", null));
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<ApiResponse<Void>> resendVerification(@RequestBody @Valid ResendVerificationReq req) {
        log.info("Received resend verification request for email: {}", req.email());
        authService.resendVerification(req);
        return ResponseEntity.ok(ApiResponse.success("Nếu email tồn tại trong hệ thống và chưa được kích hoạt, liên kết xác minh mới đã được gửi đi.", null));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<JwtRes>> login(@RequestBody @Valid LoginReq req) {
        log.info("Received login request for email: {}", req.email());
        JwtRes jwtRes = authService.login(req);
        return ResponseEntity.ok(ApiResponse.success("Đăng nhập thành công", jwtRes));
    }

    @PostMapping({"/refresh-token", "/refresh"})
    public ResponseEntity<ApiResponse<TokenResponseDTO>> refreshToken(@RequestBody @Valid RefreshTokenReq req) {
        log.info("Received refresh token request");
        TokenResponseDTO response = authService.refreshToken(req);
        return ResponseEntity.ok(ApiResponse.success("Cấp lại token thành công", response));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(@RequestBody @Valid ForgotPasswordReq req) {
        log.info("Received forgot password request for email: {}", req.email());
        authService.forgotPassword(req);
        return ResponseEntity.ok(ApiResponse.success("Nếu email tồn tại trong hệ thống, hướng dẫn đặt lại mật khẩu đã được gửi đi.", null));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@RequestBody @Valid ResetPasswordReq req) {
        log.info("Received reset password request");
        authService.resetPassword(req);
        return ResponseEntity.ok(ApiResponse.success("Đặt lại mật khẩu thành công. Vui lòng đăng nhập với mật khẩu mới.", null));
    }

    @PostMapping("/change-password")
    public ResponseEntity<ApiResponse<Void>> changePassword(@RequestBody @Valid ChangePasswordReq req) {
        UUID userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED, "Yêu cầu đăng nhập");
        }
        log.info("Received change password request for userId: {}", userId);
        authService.changePassword(userId, req);
        return ResponseEntity.ok(ApiResponse.success("Đổi mật khẩu thành công. Vui lòng đăng nhập lại.", null));
    }

    @PostMapping("/revoke-all-sessions")
    public ResponseEntity<ApiResponse<Void>> revokeAllSessions() {
        UUID userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED, "Yêu cầu đăng nhập");
        }
        log.info("Received revoke all sessions request for userId: {}", userId);
        authService.revokeAllSessions(userId);
        return ResponseEntity.ok(ApiResponse.success("Đã thu hồi tất cả các phiên đăng nhập.", null));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @RequestBody(required = false) @Valid LogoutReq req) {
        log.info("Received logout request");
        String accessToken = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            accessToken = authHeader.substring(7).trim();
        } else if (req != null && req.token() != null && !req.token().isBlank()) {
            accessToken = req.token();
        }

        if (accessToken == null || accessToken.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Access token is required for logout");
        }

        String refreshToken = (req != null) ? req.refreshToken() : null;
        authService.logout(accessToken, refreshToken);
        return ResponseEntity.ok(ApiResponse.success("Đăng xuất thành công", null));
    }
}
