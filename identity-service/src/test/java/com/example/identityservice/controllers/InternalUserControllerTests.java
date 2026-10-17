package com.example.identityservice.controllers;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.constant.SecurityConstants;
import com.example.identityservice.models.constants.RoleName;
import com.example.identityservice.models.constants.UserStatus;
import com.example.identityservice.models.entities.Role;
import com.example.identityservice.models.entities.User;
import com.example.identityservice.models.repositories.RoleRepository;
import com.example.identityservice.models.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "security.internal-service-keys.order-service=test-order-key-32chars-long-123456",
        "security.internal-service-keys.notification-service=test-notification-key-32chars-123",
        "security.internal-service-keys.inventory-service=test-inventory-key-32chars-123"
})
class InternalUserControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User targetUser;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        roleRepository.deleteAll();

        Role customerRole = roleRepository.save(Role.builder()
                .roleName(RoleName.ROLE_CUSTOMER)
                .description("Customer")
                .build());

        targetUser = userRepository.save(User.builder()
                .email("target.customer@example.com")
                .fullName("Target Customer")
                .phoneNumber("0933333333")
                .passwordHash(passwordEncoder.encode("SecretPass123@"))
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .roles(Set.of(customerRole))
                .build());
    }

    @Test
    @DisplayName("POS-12: order-service calls internal endpoint with valid key -> 200 OK")
    void testOrderServiceCallsInternalEndpoint() throws Exception {
        mockMvc.perform(get("/internal/users/" + targetUser.getId())
                        .header(SecurityConstants.HEADER_CALLING_SERVICE, "order-service")
                        .header(SecurityConstants.HEADER_INTERNAL_SERVICE_KEY, "test-order-key-32chars-long-123456"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(targetUser.getId().toString()))
                .andExpect(jsonPath("$.data.email").value("target.customer@example.com"))
                .andExpect(jsonPath("$.data.fullName").value("Target Customer"))
                .andExpect(jsonPath("$.data.roles[0]").value("ROLE_CUSTOMER"));
    }

    @Test
    @DisplayName("POS-13: notification-service calls internal endpoint with valid key -> 200 OK")
    void testNotificationServiceCallsInternalEndpoint() throws Exception {
        mockMvc.perform(get("/internal/users/" + targetUser.getId())
                        .header(SecurityConstants.HEADER_CALLING_SERVICE, "notification-service")
                        .header(SecurityConstants.HEADER_INTERNAL_SERVICE_KEY, "test-notification-key-32chars-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.email").value("target.customer@example.com"));
    }

    @Test
    @DisplayName("NEG-09: inventory-service has valid key but lacks role authorization -> 403 ACCESS_DENIED")
    void testInventoryServiceForbidden() throws Exception {
        mockMvc.perform(get("/internal/users/" + targetUser.getId())
                        .header(SecurityConstants.HEADER_CALLING_SERVICE, "inventory-service")
                        .header(SecurityConstants.HEADER_INTERNAL_SERVICE_KEY, "test-inventory-key-32chars-123"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.name()));
    }

    @Test
    @DisplayName("NEG-10: Attacker tries Gateway Assertion header on internal endpoint -> 403 ACCESS_DENIED")
    void testGatewayAssertionBlockedOnInternalEndpoint() throws Exception {
        mockMvc.perform(get("/internal/users/" + targetUser.getId())
                        .header(SecurityConstants.HEADER_GATEWAY_ASSERTION, "some-forged-jwt")
                        .header(SecurityConstants.HEADER_CALLING_SERVICE, "order-service")
                        .header(SecurityConstants.HEADER_INTERNAL_SERVICE_KEY, "test-order-key-32chars-long-123456"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.name()));
    }

    @Test
    @DisplayName("NEG-11: Missing internal auth headers -> 401 AUTHENTICATION_REQUIRED")
    void testMissingInternalHeaders() throws Exception {
        mockMvc.perform(get("/internal/users/" + targetUser.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_REQUIRED.name()));
    }

    @Test
    @DisplayName("NEG-12: Non-existent user ID -> 404 RESOURCE_NOT_FOUND")
    void testUserNotFound() throws Exception {
        mockMvc.perform(get("/internal/users/" + UUID.randomUUID())
                        .header(SecurityConstants.HEADER_CALLING_SERVICE, "order-service")
                        .header(SecurityConstants.HEADER_INTERNAL_SERVICE_KEY, "test-order-key-32chars-long-123456"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.RESOURCE_NOT_FOUND.name()));
    }
}
