package com.example.identityservice.security;

import com.example.identityservice.models.constants.RoleName;
import com.example.identityservice.models.constants.UserStatus;
import com.example.identityservice.models.entities.Role;
import com.example.identityservice.models.entities.User;
import com.example.identityservice.security.jwt.JwtUtils;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "security.internal-service-keys.order-service=test-order-key-32chars-long-123456",
        "security.internal-service-keys.notification-service=test-notification-key-32chars-123"
})
class SecurityLayerTests {

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("PasswordEncoder: should encode and verify password using BCrypt")
    void testPasswordEncoder() {
        String rawPassword = "StrongPassword123@";
        String encoded = passwordEncoder.encode(rawPassword);

        assertThat(encoded).startsWith("$2a$12$");
        assertThat(passwordEncoder.matches(rawPassword, encoded)).isTrue();
        assertThat(passwordEncoder.matches("WrongPassword", encoded)).isFalse();
    }

    @Test
    @DisplayName("JwtUtils: should generate valid token containing sub (userId), email, roles and jti")
    void testJwtGenerationAndExtraction() {
        UUID userId = UUID.randomUUID();
        User user = User.builder()
                .id(userId)
                .email("jwt@example.com")
                .passwordHash("hash")
                .fullName("JWT User")
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .roles(Set.of(Role.builder().roleName(RoleName.ROLE_CUSTOMER).build()))
                .build();

        String token = jwtUtils.generateAccessToken(user);
        assertThat(token).isNotBlank();
        assertThat(jwtUtils.validateToken(token)).isTrue();

        Claims claims = jwtUtils.extractAllClaims(token);
        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get("email")).isEqualTo("jwt@example.com");
        assertThat(claims.getId()).isNotBlank(); // jti

        @SuppressWarnings("unchecked")
        List<String> roles = claims.get("roles", List.class);
        assertThat(roles).contains("ROLE_CUSTOMER");
    }

    @Autowired
    private com.example.identityservice.security.principal.MyUserDetailsService userDetailsService;

    @Autowired
    private com.example.identityservice.models.repositories.UserRepository userRepository;

    @Autowired
    private com.example.identityservice.models.repositories.RoleRepository roleRepository;

    @Test
    @DisplayName("MyUserDetailsService: should load user details by email")
    void testMyUserDetailsService() {
        Role role = roleRepository.save(Role.builder().roleName(RoleName.ROLE_ADMINISTRATOR).description("Admin").build());
        User user = userRepository.save(User.builder()
                .email("details@example.com")
                .passwordHash("$2a$12$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy")
                .fullName("Details User")
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .roles(Set.of(role))
                .build());

        var userDetails = userDetailsService.loadUserByUsername("details@example.com");
        assertThat(userDetails).isNotNull();
        assertThat(userDetails.getUsername()).isEqualTo("details@example.com");
        assertThat(userDetails.getAuthorities())
                .extracting("authority")
                .contains("ROLE_ADMINISTRATOR");
        assertThat(userDetails.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("Internal Endpoint: should reject request with X-Gateway-Assertion")
    void testInternalEndpointRejectsGatewayAssertion() throws Exception {
        mockMvc.perform(get("/internal/users/" + UUID.randomUUID())
                        .header("X-Gateway-Assertion", "dummy-assertion"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Admin Endpoint: should reject unauthenticated request with 401")
    void testAdminEndpointUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isUnauthorized());
    }
}
