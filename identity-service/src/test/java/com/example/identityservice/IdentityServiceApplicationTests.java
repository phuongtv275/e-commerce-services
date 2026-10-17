package com.example.identityservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@org.springframework.test.context.TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class IdentityServiceApplicationTests {

    @Test
    void contextLoads() {
    }

    @Test
    void securityConfigurationValidator_shouldFailWhenAdminUsesDefaultPasswordWithDifferentSalt() {
        var app = new IdentityServiceApplication();
        var env = org.mockito.Mockito.mock(org.springframework.core.env.Environment.class);
        org.mockito.Mockito.when(env.matchesProfiles("prod", "production")).thenReturn(true);
        var userRepo = org.mockito.Mockito.mock(com.example.identityservice.models.repositories.UserRepository.class);
        var encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12);

        // Generate a new distinct BCrypt hash with random salt for "Admin@123456"
        String newSaltHash = encoder.encode("Admin@123456");
        var admin = new com.example.identityservice.models.entities.User();
        admin.setEmail("admin@ecom.local");
        admin.setPasswordHash(newSaltHash);

        org.mockito.Mockito.when(userRepo.findByEmailAndDeletedAtIsNull("admin@ecom.local"))
                .thenReturn(java.util.Optional.of(admin));

        var runner = app.securityConfigurationValidator(
                env,
                userRepo,
                encoder,
                "a-very-strong-production-jwt-secret-key-32chars-min!",
                "custom-prod-public-key-not-matching-dev",
                "custom-order-secret-key-32chars-min!",
                "custom-notif-secret-key-32chars-min!"
        );

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> runner.run(null));
    }

    @Test
    void securityConfigurationValidator_shouldPassWhenAdminUsesRotatedPassword() {
        var app = new IdentityServiceApplication();
        var env = org.mockito.Mockito.mock(org.springframework.core.env.Environment.class);
        org.mockito.Mockito.when(env.matchesProfiles("prod", "production")).thenReturn(true);
        var userRepo = org.mockito.Mockito.mock(com.example.identityservice.models.repositories.UserRepository.class);
        var encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12);

        String rotatedPasswordHash = encoder.encode("SuperSafeRotatedAdmin#2026");
        var admin = new com.example.identityservice.models.entities.User();
        admin.setEmail("admin@ecom.local");
        admin.setPasswordHash(rotatedPasswordHash);

        org.mockito.Mockito.when(userRepo.findByEmailAndDeletedAtIsNull("admin@ecom.local"))
                .thenReturn(java.util.Optional.of(admin));

        var runner = app.securityConfigurationValidator(
                env,
                userRepo,
                encoder,
                "a-very-strong-production-jwt-secret-key-32chars-min!",
                "custom-prod-public-key-not-matching-dev",
                "custom-order-secret-key-32chars-min!",
                "custom-notif-secret-key-32chars-min!"
        );

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> runner.run(null));
    }

}
