package com.example.inventoryservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EntityScan(basePackages = {"com.example.inventoryservice.entity", "com.ecommerce.common.outbox", "com.ecommerce.common.idempotency"})
@EnableJpaRepositories(basePackages = {"com.example.inventoryservice.repository", "com.ecommerce.common.outbox", "com.ecommerce.common.idempotency"})
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
