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
    public ApplicationRunner adminBootstrapSecurityCheck(UserRepository userRepository) {
        return args -> {
            try {
                userRepository.findByEmailAndDeletedAtIsNull("admin@ecom.local").ifPresent(admin -> {
                    if ("$2a$12$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy".equals(admin.getPasswordHash())) {
                        log.warn("SECURITY WARNING: Default development password for 'admin@ecom.local' is active. Please change this administrative password before production deployment!");
                    }
                });
            } catch (Exception e) {
                log.debug("Skipping admin security check: {}", e.getMessage());
            }
        };
    }
}
