package com.example.identityservice.models.services.impl;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.constant.SecurityConstants;
import com.ecommerce.common.event.UserRegisteredEvent;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.exception.ConflictException;
import com.ecommerce.common.exception.ValidationException;
import com.ecommerce.common.outbox.OutboxEvent;
import com.ecommerce.common.outbox.OutboxEventRepository;
import com.ecommerce.common.outbox.OutboxStatus;
import com.example.identityservice.exceptions.NotFoundException;
import com.example.identityservice.models.constants.RoleName;
import com.example.identityservice.models.constants.UserStatus;
import com.example.identityservice.models.dto.req.*;
import com.example.identityservice.models.dto.res.JwtRes;
import com.example.identityservice.models.dto.res.TokenResponseDTO;
import com.example.identityservice.models.entities.PasswordReset;
import com.example.identityservice.models.entities.RefreshToken;
import com.example.identityservice.models.entities.Role;
import com.example.identityservice.models.entities.User;
import com.example.identityservice.models.repositories.PasswordResetRepository;
import com.example.identityservice.models.repositories.RefreshTokenRepository;
import com.example.identityservice.models.repositories.RoleRepository;
import com.example.identityservice.models.repositories.UserRepository;
import com.example.identityservice.models.services.AuditLogService;
import com.example.identityservice.models.services.AuthService;
import com.example.identityservice.models.services.RedisBlacklistService;
import com.example.identityservice.models.services.RefreshTokenService;
import com.example.identityservice.security.SecurityUtils;
import com.example.identityservice.security.jwt.JwtUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetRepository passwordResetRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final AuditLogService auditLogService;
    private final RefreshTokenService refreshTokenService;
    private final RedisBlacklistService redisBlacklistService;
    private final JwtUtils jwtUtils;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;

    private static String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    private void saveUserRegisteredOutboxEvent(User user, String rawVerificationToken) {
        UUID eventId = UUID.randomUUID();
        String correlationId = MDC.get(SecurityConstants.MDC_CORRELATION_ID);
        if (correlationId == null) {
            correlationId = MDC.get("correlationId");
        }

        UserRegisteredEvent registeredEvent = UserRegisteredEvent.builder()
                .eventId(eventId)
                .eventType("user.registered")
                .eventVersion(1)
                .occurredAt(Instant.now())
                .correlationId(correlationId)
                .producer("identity-service")
                .userId(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .verificationToken(rawVerificationToken)
                .build();

        try {
            String payload = objectMapper.writeValueAsString(registeredEvent);

            OutboxEvent event = OutboxEvent.builder()
                    .id(eventId)
                    .aggregateType("USER")
                    .aggregateId(user.getId().toString())
                    .eventType("user.registered")
                    .eventVersion(1)
                    .payload(payload)
                    .status(OutboxStatus.PENDING)
                    .retryCount(0)
                    .createdAt(Instant.now())
                    .correlationId(correlationId)
                    .build();
            outboxEventRepository.save(event);
            log.info("Saved outbox event [user.registered] eventId={}", eventId);
        } catch (Exception e) {
            log.error("Failed to serialize user.registered outbox event: {}", e.getMessage(), e);
            throw new RuntimeException("Could not record outbox event", e);
        }
    }

    private void saveOutboxEvent(String eventType, User user, String token) {
        try {
            Map<String, Object> payloadMap = new HashMap<>();
            payloadMap.put("userId", user.getId().toString());
            payloadMap.put("email", user.getEmail());
            payloadMap.put("fullName", user.getFullName());
            if (token != null) {
                payloadMap.put("token", token);
            }
            String payload = objectMapper.writeValueAsString(payloadMap);

            OutboxEvent event = OutboxEvent.builder()
                    .id(UUID.randomUUID())
                    .aggregateType("USER")
                    .aggregateId(user.getId().toString())
                    .eventType(eventType)
                    .eventVersion(1)
                    .payload(payload)
                    .status(OutboxStatus.PENDING)
                    .retryCount(0)
                    .createdAt(Instant.now())
                    .correlationId(MDC.get("correlationId"))
                    .build();
            outboxEventRepository.save(event);
            log.info("Saved outbox event [{}] for user: {}", eventType, user.getEmail());
        } catch (Exception e) {
            log.error("Failed to serialize outbox event payload: {}", e.getMessage(), e);
            throw new RuntimeException("Could not record outbox event", e);
        }
    }

    @Override
    @Transactional
    public void register(RegisterReq req) {
        String email = req.email().trim().toLowerCase();
        log.info("Processing registration for email: {}", email);

        if (userRepository.existsByEmailAndDeletedAtIsNull(email)) {
            log.warn("Registration rejected: email already registered [{}]", email);
            throw new ConflictException(ErrorCode.STATE_CONFLICT, "Email đã được đăng ký trong hệ thống");
        }

        Role customerRole = roleRepository.findByRoleName(RoleName.ROLE_CUSTOMER)
                .orElseGet(() -> roleRepository.save(Role.builder()
                        .roleName(RoleName.ROLE_CUSTOMER)
                        .description("Khách hàng mặc định")
                        .build()));

        String rawVerificationToken = UUID.randomUUID().toString();
        String verificationTokenHash = hashToken(rawVerificationToken);

        User user = User.builder()
                .email(email)
                .fullName(req.fullName().trim())
                .phoneNumber(req.phoneNumber())
                .passwordHash(passwordEncoder.encode(req.password()))
                .status(UserStatus.PENDING_VERIFICATION)
                .emailVerified(false)
                .verificationTokenHash(verificationTokenHash)
                .verificationExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS))
                .roles(new HashSet<>(Set.of(customerRole)))
                .build();

        User savedUser = userRepository.save(user);

        // Transactional Outbox event
        saveUserRegisteredOutboxEvent(savedUser, rawVerificationToken);

        // Immutable Audit Log
        auditLogService.logAction(
                savedUser.getId(),
                savedUser.getEmail(),
                "USER_REGISTER",
                "USER",
                savedUser.getId().toString(),
                "{\"status\":\"PENDING_VERIFICATION\"}",
                null,
                MDC.get("correlationId")
        );
        log.info("Successfully registered user: {}", email);
    }

    @Override
    @Transactional
    public void verifyEmail(VerifyEmailReq req) {
        String tokenHash = hashToken(req.token());
        User user = userRepository.findByVerificationTokenHashAndDeletedAtIsNull(tokenHash)
                .orElseThrow(() -> new ValidationException("Mã xác thực email không hợp lệ hoặc đã hết hạn"));

        if (user.getVerificationExpiresAt() != null && user.getVerificationExpiresAt().isBefore(Instant.now())) {
            throw new ValidationException("Mã xác thực email không hợp lệ hoặc đã hết hạn");
        }

        user.setEmailVerified(true);
        user.setStatus(UserStatus.ACTIVE);
        user.setVerificationTokenHash(null);
        user.setVerificationExpiresAt(null);
        userRepository.save(user);

        auditLogService.logAction(
                user.getId(),
                user.getEmail(),
                "EMAIL_VERIFIED",
                "USER",
                user.getId().toString(),
                "{\"emailVerified\":true,\"status\":\"ACTIVE\"}",
                null,
                MDC.get("correlationId")
        );
        log.info("Email verified successfully for user: {}", user.getEmail());
    }

    @Override
    @Transactional
    public void resendVerification(ResendVerificationReq req) {
        String email = req.email().trim().toLowerCase();
        log.info("Resend verification requested for email: {}", email);

        // Anti-user enumeration: Always return quietly to caller
        userRepository.findByEmailAndDeletedAtIsNull(email).ifPresent(user -> {
            if (!user.isEmailVerified()) {
                String rawToken = UUID.randomUUID().toString();
                user.setVerificationTokenHash(hashToken(rawToken));
                user.setVerificationExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
                userRepository.save(user);

                saveUserRegisteredOutboxEvent(user, rawToken);

                auditLogService.logAction(
                        user.getId(),
                        user.getEmail(),
                        "RESEND_VERIFICATION",
                        "USER",
                        user.getId().toString(),
                        "{}",
                        null,
                        MDC.get("correlationId")
                );
            }
        });
    }

    @Override
    @Transactional
    public JwtRes login(LoginReq req) {
        String email = req.email().trim().toLowerCase();
        log.info("Processing login request for email: {}", email);

        Optional<User> userOpt = userRepository.findWithRolesByEmail(email);

        // Anti-user enumeration: Uniform 401 error
        if (userOpt.isEmpty()
                || !passwordEncoder.matches(req.password(), userOpt.get().getPasswordHash())
                || !userOpt.get().isEmailVerified()
                || userOpt.get().getStatus() != UserStatus.ACTIVE) {
            log.warn("Login failed for email: [{}]. Returning uniform 401 error.", email);
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED,
                    "Email hoặc mật khẩu không chính xác, hoặc tài khoản chưa được kích hoạt.");
        }

        User user = userOpt.get();

        String accessToken = jwtUtils.generateAccessToken(user);
        RefreshToken refreshToken = refreshTokenService.createRefreshToken(user);

        List<String> roles = user.getRoles() != null
                ? user.getRoles().stream().map(r -> r.getRoleName().name()).toList()
                : List.of();

        auditLogService.logAction(
                user.getId(),
                user.getEmail(),
                "LOGIN_SUCCESS",
                "USER",
                user.getId().toString(),
                "{}",
                null,
                MDC.get("correlationId")
        );

        return JwtRes.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken.getToken())
                .tokenType("Bearer")
                .expiresIn(900L)
                .roles(roles)
                .build();
    }

    @Override
    public TokenResponseDTO refreshToken(RefreshTokenReq req) {
        log.info("Processing refreshToken in AuthService");
        return refreshTokenService.refreshToken(req.refreshToken());
    }

    @Override
    @Transactional
    public void logout(String accessToken, String refreshToken) {
        log.info("Processing logout request");
        if (accessToken == null || accessToken.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Access token is required for logout");
        }

        String cleanToken = accessToken.startsWith("Bearer ") ? accessToken.substring(7).trim() : accessToken.trim();
        Claims claims = redisBlacklistService.blacklistToken(cleanToken);

        if (refreshToken != null && !refreshToken.isBlank()) {
            String tokenHash = hashToken(refreshToken.trim());
            refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(rt -> {
                rt.setRevoked(true);
                refreshTokenRepository.save(rt);
            });
        }

        String subject = claims != null ? claims.getSubject() : null;
        UUID userId = null;
        String email = claims != null ? claims.get("email", String.class) : null;
        if (subject != null) {
            try {
                userId = UUID.fromString(subject);
            } catch (IllegalArgumentException ignored) {
            }
        }

        auditLogService.logAction(
                userId,
                email,
                "LOGOUT",
                "USER",
                subject,
                "{}",
                null,
                MDC.get("correlationId")
        );
        log.info("Logout successfully processed");
    }

    @Override
    @Transactional
    public void forgotPassword(ForgotPasswordReq req) {
        String email = req.email().trim().toLowerCase();
        log.info("Forgot password requested for email: {}", email);

        // Anti-user enumeration: Always return quietly to caller
        userRepository.findByEmailAndDeletedAtIsNull(email).ifPresent(user -> {
            if (user.getStatus() == UserStatus.ACTIVE) {
                String rawResetToken = UUID.randomUUID().toString();
                String tokenHash = hashToken(rawResetToken);

                PasswordReset reset = PasswordReset.builder()
                        .user(user)
                        .tokenHash(tokenHash)
                        .expiresAt(Instant.now().plus(15, ChronoUnit.MINUTES))
                        .used(false)
                        .build();
                passwordResetRepository.save(reset);

                saveOutboxEvent("PasswordResetRequestedEvent", user, rawResetToken);

                auditLogService.logAction(
                        user.getId(),
                        user.getEmail(),
                        "PASSWORD_RESET_REQUESTED",
                        "USER",
                        user.getId().toString(),
                        "{}",
                        null,
                        MDC.get("correlationId")
                );
            }
        });
    }

    @Override
    @Transactional
    public void resetPassword(ResetPasswordReq req) {
        String tokenHash = hashToken(req.token().trim());
        log.info("Processing password reset with token hash");

        // Atomic CAS SQL query: UPDATE password_resets SET used=true WHERE token_hash=:h AND used=false AND expires_at > now()
        int updated = passwordResetRepository.consumeResetTokenAtomic(tokenHash, Instant.now());
        if (updated == 0) {
            log.warn("Atomic CAS failed: Reset token invalid, already consumed, or expired");
            throw new ValidationException("Mã đặt lại mật khẩu không hợp lệ, đã được sử dụng hoặc đã hết hạn");
        }

        PasswordReset reset = passwordResetRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new ValidationException("Mã đặt lại mật khẩu không hợp lệ"));

        User user = reset.getUser();
        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        userRepository.save(user);

        // Security: Revoke all existing sessions across devices
        refreshTokenRepository.revokeAllUserSessions(user.getId());

        auditLogService.logAction(
                user.getId(),
                user.getEmail(),
                "PASSWORD_RESET_SUCCESS",
                "USER",
                user.getId().toString(),
                "{}",
                null,
                MDC.get("correlationId")
        );
        log.info("Password reset successfully completed for user: {}", user.getEmail());
    }

    @Override
    @Transactional
    public void changePassword(UUID userId, ChangePasswordReq req) {
        log.info("Processing change password for userId: {}", userId);
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new NotFoundException("User not found with id: " + userId));

        if (!passwordEncoder.matches(req.oldPassword(), user.getPasswordHash())) {
            log.warn("Change password failed: Old password mismatch for user: {}", user.getEmail());
            throw new ValidationException("Mật khẩu hiện tại không chính xác");
        }

        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        userRepository.save(user);

        // Security: Revoke all existing sessions across devices
        refreshTokenRepository.revokeAllUserSessions(userId);

        auditLogService.logAction(
                userId,
                user.getEmail(),
                "PASSWORD_CHANGE_SUCCESS",
                "USER",
                userId.toString(),
                "{}",
                null,
                MDC.get("correlationId")
        );
        log.info("Password changed successfully for user: {}", user.getEmail());
    }

    @Override
    @Transactional
    public void revokeAllSessions(UUID userId) {
        log.info("Revoking all sessions for userId: {}", userId);
        refreshTokenRepository.revokeAllUserSessions(userId);

        auditLogService.logAction(
                userId,
                SecurityUtils.getCurrentUserEmail(),
                "REVOKE_ALL_SESSIONS",
                "USER",
                userId != null ? userId.toString() : null,
                "{}",
                null,
                MDC.get("correlationId")
        );
    }
}
