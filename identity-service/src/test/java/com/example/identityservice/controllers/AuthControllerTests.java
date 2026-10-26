package com.example.identityservice.controllers;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.event.UserRegisteredEvent;
import com.ecommerce.common.outbox.OutboxEvent;
import com.ecommerce.common.outbox.OutboxEventRepository;
import com.example.identityservice.models.constants.RoleName;
import com.example.identityservice.models.constants.UserStatus;
import com.example.identityservice.models.dto.req.*;
import com.example.identityservice.models.entities.PasswordReset;
import com.example.identityservice.models.entities.RefreshToken;
import com.example.identityservice.models.entities.Role;
import com.example.identityservice.models.entities.User;
import com.example.identityservice.models.repositories.PasswordResetRepository;
import com.example.identityservice.models.repositories.RefreshTokenRepository;
import com.example.identityservice.models.repositories.RoleRepository;
import com.example.identityservice.models.repositories.UserRepository;
import com.example.identityservice.models.services.RedisBlacklistService;
import com.example.identityservice.models.services.impl.RefreshTokenServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AuthControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordResetRepository passwordResetRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private RedisBlacklistService redisBlacklistService;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        passwordResetRepository.deleteAll();
        outboxEventRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        roleRepository.save(Role.builder().roleName(RoleName.ROLE_CUSTOMER).description("Customer").build());
        roleRepository.save(Role.builder().roleName(RoleName.ROLE_ADMINISTRATOR).description("Admin").build());

        Claims mockClaims = Mockito.mock(Claims.class);
        Mockito.when(mockClaims.getId()).thenReturn(UUID.randomUUID().toString());
        Mockito.when(mockClaims.getSubject()).thenReturn(UUID.randomUUID().toString());
        Mockito.when(redisBlacklistService.blacklistToken(anyString())).thenReturn(mockClaims);
    }

    @Test
    @DisplayName("POS-01: User registration creates pending user and outbox event")
    void testRegisterSuccess() throws Exception {
        RegisterReq req = new RegisterReq("Nguyễn Văn A", "register@example.com", "Password123@", "0912345678");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        User user = userRepository.findByEmailAndDeletedAtIsNull("register@example.com").orElse(null);
        assertThat(user).isNotNull();
        assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING_VERIFICATION);
        assertThat(user.isEmailVerified()).isFalse();
        assertThat(user.getVerificationTokenHash()).isNotBlank();

        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertThat(events).hasSize(1);
        OutboxEvent outboxEvent = events.get(0);
        assertThat(outboxEvent.getEventType()).isEqualTo("user.registered");
        UserRegisteredEvent registeredPayload = objectMapper.readValue(outboxEvent.getPayload(), UserRegisteredEvent.class);
        assertThat(registeredPayload.getEventId()).isEqualTo(outboxEvent.getId());
        assertThat(registeredPayload.getEventType()).isEqualTo("user.registered");
        assertThat(registeredPayload.getEventVersion()).isEqualTo(1);
        assertThat(registeredPayload.getProducer()).isEqualTo("identity-service");
        assertThat(registeredPayload.getUserId()).isEqualTo(user.getId());
        assertThat(registeredPayload.getEmail()).isEqualTo("register@example.com");
        assertThat(registeredPayload.getFullName()).isEqualTo("Nguyễn Văn A");
        assertThat(registeredPayload.getVerificationToken()).isNotBlank();
    }

    @Test
    @DisplayName("POS-RESEND-01: Resend verification publishes new user.registered event with distinct eventId")
    void testResendVerification_PublishesDistinctUserRegisteredEvent() throws Exception {
        RegisterReq req = new RegisterReq("Trần Thị B", "resend@example.com", "Password123@", "0987654321");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        List<OutboxEvent> initialEvents = outboxEventRepository.findAll();
        assertThat(initialEvents).hasSize(1);
        OutboxEvent registerEvent = initialEvents.get(0);
        UserRegisteredEvent regPayload = objectMapper.readValue(registerEvent.getPayload(), UserRegisteredEvent.class);

        ResendVerificationReq resendReq = new ResendVerificationReq("resend@example.com");
        mockMvc.perform(post("/api/v1/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resendReq)))
                .andExpect(status().isOk());

        List<OutboxEvent> allEvents = outboxEventRepository.findAll();
        assertThat(allEvents).hasSize(2);

        OutboxEvent resendEvent = allEvents.get(1);
        assertThat(resendEvent.getEventType()).isEqualTo("user.registered");
        assertThat(resendEvent.getId()).isNotEqualTo(registerEvent.getId());

        UserRegisteredEvent resendPayload = objectMapper.readValue(resendEvent.getPayload(), UserRegisteredEvent.class);
        assertThat(resendPayload.getEventId()).isEqualTo(resendEvent.getId());
        assertThat(resendPayload.getEventId()).isNotEqualTo(regPayload.getEventId());
        assertThat(resendPayload.getEventType()).isEqualTo("user.registered");
        assertThat(resendPayload.getUserId()).isEqualTo(regPayload.getUserId());
        assertThat(resendPayload.getEmail()).isEqualTo("resend@example.com");
        assertThat(resendPayload.getFullName()).isEqualTo("Trần Thị B");
        assertThat(resendPayload.getVerificationToken()).isNotBlank();
        assertThat(resendPayload.getVerificationToken()).isNotEqualTo(regPayload.getVerificationToken());
    }

    @Test
    @DisplayName("NEG-RESEND-01: Resend verification for already verified user does not publish new event")
    void testResendVerification_AlreadyVerified_NoNewEvent() throws Exception {
        RegisterReq req = new RegisterReq("Lê Văn C", "verified@example.com", "Password123@", "0987654322");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        OutboxEvent regEvent = outboxEventRepository.findAll().get(0);
        UserRegisteredEvent regPayload = objectMapper.readValue(regEvent.getPayload(), UserRegisteredEvent.class);

        // Verify account
        VerifyEmailReq verifyReq = new VerifyEmailReq(regPayload.getVerificationToken());
        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(verifyReq)))
                .andExpect(status().isOk());

        int countBefore = outboxEventRepository.findAll().size();

        // Resend request
        ResendVerificationReq resendReq = new ResendVerificationReq("verified@example.com");
        mockMvc.perform(post("/api/v1/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resendReq)))
                .andExpect(status().isOk());

        int countAfter = outboxEventRepository.findAll().size();
        assertThat(countAfter).isEqualTo(countBefore);
    }

    @Test
    @DisplayName("NEG-01: Registration with existing email returns 409 Conflict")
    void testRegisterDuplicateEmail() throws Exception {
        RegisterReq req = new RegisterReq("Nguyễn Văn A", "dup@example.com", "Password123@", "0912345678");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.STATE_CONFLICT.name()));
    }

    @Test
    @DisplayName("POS-02: Email verification activates account")
    void testVerifyEmailSuccess() throws Exception {
        String rawToken = UUID.randomUUID().toString();
        String tokenHash = RefreshTokenServiceImpl.hashToken(rawToken);

        User user = userRepository.save(User.builder()
                .email("verify@example.com")
                .fullName("Verify User")
                .passwordHash(passwordEncoder.encode("Password123@"))
                .status(UserStatus.PENDING_VERIFICATION)
                .emailVerified(false)
                .verificationTokenHash(tokenHash)
                .verificationExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS))
                .build());

        VerifyEmailReq req = new VerifyEmailReq(rawToken);

        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        User updated = userRepository.findById(user.getId()).orElseThrow();
        assertThat(updated.isEmailVerified()).isTrue();
        assertThat(updated.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(updated.getVerificationTokenHash()).isNull();
    }

    @Test
    @DisplayName("POS-04: Login with active & verified user returns accessToken and refreshToken")
    void testLoginSuccess() throws Exception {
        Role customerRole = roleRepository.findByRoleName(RoleName.ROLE_CUSTOMER).orElseThrow();
        userRepository.save(User.builder()
                .email("login@example.com")
                .fullName("Login User")
                .passwordHash(passwordEncoder.encode("Password123@"))
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .roles(Set.of(customerRole))
                .build());

        LoginReq req = new LoginReq("login@example.com", "Password123@");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"));
    }

    @Test
    @DisplayName("NEG-02 & NEG-03: Login anti-enumeration returns 401 for wrong password or unverified account")
    void testLoginAntiEnumeration() throws Exception {
        // Unverified user
        userRepository.save(User.builder()
                .email("unverified@example.com")
                .fullName("Unverified")
                .passwordHash(passwordEncoder.encode("Password123@"))
                .status(UserStatus.PENDING_VERIFICATION)
                .emailVerified(false)
                .build());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginReq("unverified@example.com", "Password123@"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_REQUIRED.name()));

        // Non-existent email
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginReq("nonexistent@example.com", "Password123@"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_REQUIRED.name()));
    }

    @Test
    @DisplayName("POS-05: Token rotation grants new token pair")
    void testTokenRotationSuccess() throws Exception {
        Role role = roleRepository.findByRoleName(RoleName.ROLE_CUSTOMER).orElseThrow();
        User user = userRepository.save(User.builder()
                .email("rotate@example.com")
                .fullName("Rotate User")
                .passwordHash(passwordEncoder.encode("Password123@"))
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .roles(Set.of(role))
                .build());

        String rawToken = UUID.randomUUID().toString();
        UUID familyId = UUID.randomUUID();
        RefreshToken refreshToken = refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .tokenHash(RefreshTokenServiceImpl.hashToken(rawToken))
                .familyId(familyId)
                .expiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
                .revoked(false)
                .build());

        RefreshTokenReq req = new RefreshTokenReq(rawToken);

        mockMvc.perform(post("/api/v1/auth/refresh-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty());

        RefreshToken oldToken = refreshTokenRepository.findById(refreshToken.getId()).orElseThrow();
        assertThat(oldToken.isRevoked()).isTrue();
    }

    @Test
    @DisplayName("NEG-04: Token reuse detection revokes entire token family with 403 Forbidden")
    void testTokenReuseAttackRevokesFamily() throws Exception {
        Role role = roleRepository.findByRoleName(RoleName.ROLE_CUSTOMER).orElseThrow();
        User user = userRepository.save(User.builder()
                .email("reuse@example.com")
                .fullName("Reuse User")
                .passwordHash(passwordEncoder.encode("Password123@"))
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .roles(Set.of(role))
                .build());

        UUID familyId = UUID.randomUUID();
        String rawToken1 = UUID.randomUUID().toString();
        String rawToken2 = UUID.randomUUID().toString();

        RefreshToken token1 = refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .tokenHash(RefreshTokenServiceImpl.hashToken(rawToken1))
                .familyId(familyId)
                .expiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
                .revoked(true) // already revoked!
                .build());

        RefreshToken token2 = refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .tokenHash(RefreshTokenServiceImpl.hashToken(rawToken2))
                .familyId(familyId)
                .expiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
                .revoked(false) // active sibling token in family
                .build());

        // Attacker attempts to replay token1
        mockMvc.perform(post("/api/v1/auth/refresh-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenReq(rawToken1))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.name()));

        // Sibling token must now be revoked as well
        RefreshToken sibling = refreshTokenRepository.findById(token2.getId()).orElseThrow();
        assertThat(sibling.isRevoked()).isTrue();
    }

    @Test
    @DisplayName("POS-06 & POS-07: Forgot password and Atomic CAS Reset Password")
    void testForgotAndResetPasswordFlow() throws Exception {
        User user = userRepository.save(User.builder()
                .email("forgot@example.com")
                .fullName("Forgot User")
                .passwordHash(passwordEncoder.encode("OldPassword123@"))
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .build());

        // Forgot password
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ForgotPasswordReq("forgot@example.com"))))
                .andExpect(status().isOk());

        List<PasswordReset> resets = passwordResetRepository.findAll();
        assertThat(resets).hasSize(1);
        PasswordReset resetRecord = resets.get(0);

        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo("PasswordResetRequestedEvent");

        // Parse raw token from outbox payload
        String payload = events.get(0).getPayload();
        String rawToken = objectMapper.readTree(payload).get("token").asText();

        // Reset password
        ResetPasswordReq resetReq = new ResetPasswordReq(rawToken, "NewPassword123@");
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resetReq)))
                .andExpect(status().isOk());

        User updatedUser = userRepository.findById(user.getId()).orElseThrow();
        assertThat(passwordEncoder.matches("NewPassword123@", updatedUser.getPasswordHash())).isTrue();

        // Replaying same reset token must fail (Atomic CAS)
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resetReq)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POS-09: Logout adds access token to Redis blacklist")
    void testLogoutSuccess() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("logout@example.com"))
                        .header("Authorization", "Bearer dummy-jwt-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        Mockito.verify(redisBlacklistService, Mockito.times(1)).blacklistToken("dummy-jwt-token");
    }
}
