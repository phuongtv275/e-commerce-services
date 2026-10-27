package com.example.identityservice.config;

import com.example.identityservice.models.constants.RoleName;
import com.example.identityservice.models.constants.UserStatus;
import com.example.identityservice.models.entities.Role;
import com.example.identityservice.models.entities.User;
import com.example.identityservice.models.repositories.RoleRepository;
import com.example.identityservice.models.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class IdentityDataSeeder implements ApplicationRunner {

    private static final String ADMIN_EMAIL = "admin@ecom.local";
    private static final String ADMIN_FULL_NAME = "System Administrator";
    private static final String ADMIN_PHONE = "0900000001";
    private static final String DEFAULT_ADMIN_PASSWORD_RAW = "Admin@123456";
    private static final String DEFAULT_ADMIN_PASSWORD_HASH =
            "$2a$12$Cy9GaGBoOzeE9axBnnbHKe7mvwXWjpLq8jnHUyGtGEcOWhoTKFisC";

    private static final Map<RoleName, String> DEFAULT_ROLE_DESCRIPTIONS = Map.of(
            RoleName.ROLE_CUSTOMER, "Khách hàng mua sắm trực tuyến",
            RoleName.ROLE_CATALOG_STAFF, "Nhân viên quản lý danh mục sản phẩm",
            RoleName.ROLE_INVENTORY_STAFF, "Nhân viên quản lý kho và tồn kho",
            RoleName.ROLE_ORDER_STAFF, "Nhân viên điều phối và quản lý đơn hàng",
            RoleName.ROLE_FINANCE_STAFF, "Nhân viên tài chính, đối soát thanh toán",
            RoleName.ROLE_ADMINISTRATOR, "Quản trị viên toàn quyền hệ thống"
    );

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        log.info("Checking and seeding default identity roles and administrator user...");
        Map<RoleName, Role> roles = seedRoles();
        seedAdministrator(roles.get(RoleName.ROLE_ADMINISTRATOR));
        log.info("Identity data seeding completed successfully.");
    }

    private Map<RoleName, Role> seedRoles() {
        Map<RoleName, Role> roleMap = new EnumMap<>(RoleName.class);
        for (RoleName roleName : RoleName.values()) {
            Role role = roleRepository.findByRoleName(roleName).orElseGet(() -> {
                log.info("Seeding missing role: {}", roleName);
                Role newRole = Role.builder()
                        .roleName(roleName)
                        .description(DEFAULT_ROLE_DESCRIPTIONS.getOrDefault(roleName, roleName.name()))
                        .build();
                return roleRepository.save(newRole);
            });
            roleMap.put(roleName, role);
        }
        return roleMap;
    }

    private void seedAdministrator(Role adminRole) {
        if (adminRole == null) {
            adminRole = roleRepository.findByRoleName(RoleName.ROLE_ADMINISTRATOR)
                    .orElseThrow(() -> new IllegalStateException("ROLE_ADMINISTRATOR not found during admin seeding"));
        }

        Optional<User> existingAdminOpt = userRepository.findByEmailAndDeletedAtIsNull(ADMIN_EMAIL);
        if (existingAdminOpt.isEmpty()) {
            log.info("Seeding default administrator user: {}", ADMIN_EMAIL);
            String passwordHash = resolveAdminPasswordHash();
            User admin = User.builder()
                    .email(ADMIN_EMAIL)
                    .passwordHash(passwordHash)
                    .fullName(ADMIN_FULL_NAME)
                    .phoneNumber(ADMIN_PHONE)
                    .status(UserStatus.ACTIVE)
                    .emailVerified(true)
                    .roles(new HashSet<>(Set.of(adminRole)))
                    .build();
            userRepository.save(admin);
            log.info("Default administrator '{}' seeded successfully.", ADMIN_EMAIL);
        } else {
            User existingAdmin = existingAdminOpt.get();
            boolean hasAdminRole = existingAdmin.getRoles().stream()
                    .anyMatch(r -> r.getRoleName() == RoleName.ROLE_ADMINISTRATOR);
            if (!hasAdminRole) {
                log.info("Assigning ROLE_ADMINISTRATOR to existing admin user '{}'", ADMIN_EMAIL);
                existingAdmin.getRoles().add(adminRole);
                userRepository.save(existingAdmin);
            }
        }
    }

    private String resolveAdminPasswordHash() {
        try {
            if (passwordEncoder != null) {
                String encoded = passwordEncoder.encode(DEFAULT_ADMIN_PASSWORD_RAW);
                if (encoded != null && !encoded.isBlank()) {
                    return encoded;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to encode admin password with injected encoder, using default hash: {}", e.getMessage());
        }
        return DEFAULT_ADMIN_PASSWORD_HASH;
    }
}
