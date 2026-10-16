package com.example.identityservice.controllers;

import com.ecommerce.common.constant.ErrorCode;
import com.example.identityservice.models.constants.RoleName;
import com.example.identityservice.models.constants.UserStatus;
import com.example.identityservice.models.dto.req.AssignRoleReq;
import com.example.identityservice.models.entities.Role;
import com.example.identityservice.models.entities.User;
import com.example.identityservice.models.repositories.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AdminControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private AddressRepository addressRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordResetRepository passwordResetRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User adminUser;
    private User regularUser;
    private Role adminRole;
    private Role customerRole;
    private Role catalogStaffRole;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        addressRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        passwordResetRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        adminRole = roleRepository.save(Role.builder()
                .roleName(RoleName.ROLE_ADMINISTRATOR)
                .description("Administrator")
                .build());

        customerRole = roleRepository.save(Role.builder()
                .roleName(RoleName.ROLE_CUSTOMER)
                .description("Customer")
                .build());

        catalogStaffRole = roleRepository.save(Role.builder()
                .roleName(RoleName.ROLE_CATALOG_STAFF)
                .description("Catalog Staff")
                .build());

        adminUser = userRepository.save(User.builder()
                .email("admin@ecommerce.com")
                .fullName("System Admin")
                .phoneNumber("0900000001")
                .passwordHash(passwordEncoder.encode("AdminPass123@"))
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .roles(Set.of(adminRole))
                .build());

        regularUser = userRepository.save(User.builder()
                .email("customer@ecommerce.com")
                .fullName("Regular Customer")
                .phoneNumber("0900000002")
                .passwordHash(passwordEncoder.encode("CustomerPass123@"))
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .roles(Set.of(customerRole))
                .build());
    }

    @Test
    @DisplayName("POS-11: Admin can list users, assign role, revoke role, and view audit logs")
    void testAdminIamWorkflow() throws Exception {
        // 1. Admin lists users
        mockMvc.perform(get("/api/v1/admin/users")
                        .with(user("admin@ecommerce.com").roles("ADMINISTRATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.totalElements").value(2));

        // 2. Admin assigns ROLE_CATALOG_STAFF to regularUser
        AssignRoleReq assignReq = new AssignRoleReq("ROLE_CATALOG_STAFF");
        mockMvc.perform(post("/api/v1/admin/users/" + regularUser.getId() + "/roles")
                        .with(user(adminUser.getId().toString()).roles("ADMINISTRATOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(assignReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        User updatedUser = userRepository.findWithRolesByEmail(regularUser.getEmail()).orElseThrow();
        assertThat(updatedUser.getRoles())
                .extracting(Role::getRoleName)
                .contains(RoleName.ROLE_CATALOG_STAFF, RoleName.ROLE_CUSTOMER);

        // 3. Admin revokes ROLE_CATALOG_STAFF from regularUser
        mockMvc.perform(delete("/api/v1/admin/users/" + regularUser.getId() + "/roles/ROLE_CATALOG_STAFF")
                        .with(user(adminUser.getId().toString()).roles("ADMINISTRATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        User revokedUser = userRepository.findWithRolesByEmail(regularUser.getEmail()).orElseThrow();
        assertThat(revokedUser.getRoles())
                .extracting(Role::getRoleName)
                .doesNotContain(RoleName.ROLE_CATALOG_STAFF)
                .contains(RoleName.ROLE_CUSTOMER);

        // 4. Admin queries audit logs
        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .with(user("admin@ecommerce.com").roles("ADMINISTRATOR"))
                        .param("action", "ROLE_ASSIGNED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].action").value("ROLE_ASSIGNED"));
    }

    @Test
    @DisplayName("NEG-08: Non-admin users are rejected with 403 ACCESS_DENIED or 401 AUTHENTICATION_REQUIRED")
    void testNonAdminAccessForbidden() throws Exception {
        // Customer calls GET /api/v1/admin/users -> 403 Forbidden
        mockMvc.perform(get("/api/v1/admin/users")
                        .with(user("customer@ecommerce.com").roles("CUSTOMER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.name()));

        // Customer calls POST /api/v1/admin/users/{userId}/roles -> 403 Forbidden
        AssignRoleReq assignReq = new AssignRoleReq("ROLE_CATALOG_STAFF");
        mockMvc.perform(post("/api/v1/admin/users/" + regularUser.getId() + "/roles")
                        .with(user("customer@ecommerce.com").roles("CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(assignReq)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.name()));

        // Unauthenticated calls GET /api/v1/admin/users -> 401 Unauthorized
        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_REQUIRED.name()));
    }
}
