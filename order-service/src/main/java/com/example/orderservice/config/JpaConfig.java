package com.example.orderservice.config;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EntityScan(basePackages = {"com.example.orderservice.entity", "com.ecommerce.common.outbox", "com.ecommerce.common.idempotency"})
@EnableJpaRepositories(basePackages = {"com.example.orderservice.repository", "com.ecommerce.common.outbox", "com.ecommerce.common.idempotency"})
public class JpaConfig {
}
