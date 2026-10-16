package com.example.identityservice;

import com.example.identityservice.models.repositories.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Slf4j
@SpringBootApplication
@EntityScan(basePackages = {"com.example.identityservice.models.entities", "com.ecommerce.common.outbox"})
@EnableJpaRepositories(basePackages = {"com.example.identityservice.models.repositories", "com.ecommerce.common.outbox"})
public class IdentityServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityServiceApplication.class, args);
    }

    @Bean
    public ApplicationRunner securityConfigurationValidator(
            org.springframework.core.env.Environment environment,
            UserRepository userRepository,
            @org.springframework.beans.factory.annotation.Autowired(required = false) org.springframework.security.crypto.password.PasswordEncoder passwordEncoder,
            @org.springframework.beans.factory.annotation.Value("${app.jwt.secret:}") String jwtSecret,
            @org.springframework.beans.factory.annotation.Value("${security.gateway-assertion.public-key:}") String gatewayAssertionPublicKey,
            @org.springframework.beans.factory.annotation.Value("${security.internal-service-keys.order-service:}") String orderKey,
            @org.springframework.beans.factory.annotation.Value("${security.internal-service-keys.notification-service:}") String notifKey
    ) {
        return args -> {
            boolean isProd = environment.matchesProfiles("prod", "production");
            String defaultDevSecret = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";
            String defaultOrderKey = "order-service-secret-key-32chars";
            String defaultNotifKey = "notification-service-secret-key-32c";
            org.springframework.security.crypto.password.PasswordEncoder encoder =
                    (passwordEncoder != null) ? passwordEncoder : new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();

            if (isProd) {
                if (jwtSecret == null || jwtSecret.isBlank() || defaultDevSecret.equals(jwtSecret.trim())) {
                    throw new IllegalStateException("CRITICAL SECURITY ERROR: In production profile, JWT_SECRET must be securely provided via environment variable and cannot use the development fallback key!");
                }
                if (gatewayAssertionPublicKey == null || gatewayAssertionPublicKey.isBlank() || gatewayAssertionPublicKey.contains("QDPhMtrH9BWAw22")) {
                    throw new IllegalStateException("CRITICAL SECURITY ERROR: In production profile, a secure non-default GATEWAY_ASSERTION_PUBLIC_KEY must be provided via environment variable!");
                }
                if (orderKey == null || defaultOrderKey.equals(orderKey.trim()) || notifKey == null || defaultNotifKey.equals(notifKey.trim())) {
                    throw new IllegalStateException("CRITICAL SECURITY ERROR: In production profile, internal service keys must be securely configured with non-default secrets!");
                }
                userRepository.findByEmailAndDeletedAtIsNull("admin@ecom.local").ifPresent(admin -> {
                    if (encoder.matches("Admin@123456", admin.getPasswordHash())) {
                        throw new IllegalStateException("CRITICAL SECURITY ERROR: Default development password for 'admin@ecom.local' is detected in production. Admin password must be rotated before deploying!");
                    }
                });
            } else {
                if (jwtSecret != null && defaultDevSecret.equals(jwtSecret.trim())) {
                    log.warn("SECURITY WARNING: Using default development HMAC secret key. Ensure JWT_SECRET is overridden in production!");
                }
                try {
                    userRepository.findByEmailAndDeletedAtIsNull("admin@ecom.local").ifPresent(admin -> {
                        if (encoder.matches("Admin@123456", admin.getPasswordHash())) {
                            log.warn("SECURITY WARNING: Default development password for 'admin@ecom.local' is active. Please change this administrative password before production deployment!");
                        }
                    });
                } catch (Exception e) {
                    log.debug("Skipping admin development security check: {}", e.getMessage());
                }
            }
        };
    }
}
