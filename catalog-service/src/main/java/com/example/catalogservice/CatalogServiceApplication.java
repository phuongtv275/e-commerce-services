package com.example.catalogservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EntityScan(basePackages = {"com.example.catalogservice.entity", "com.ecommerce.common.outbox", "com.ecommerce.common.idempotency"})
@EnableJpaRepositories(basePackages = {"com.example.catalogservice.repository", "com.ecommerce.common.outbox", "com.ecommerce.common.idempotency"})
public class CatalogServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogServiceApplication.class, args);
    }

}
