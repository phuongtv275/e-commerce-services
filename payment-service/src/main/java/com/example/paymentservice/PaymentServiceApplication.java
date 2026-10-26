package com.example.paymentservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@ConfigurationPropertiesScan
@EntityScan(basePackages = {
        "com.example.paymentservice.entity",
        "com.ecommerce.common.outbox",
        "com.ecommerce.common.idempotency"
})
@EnableJpaRepositories(basePackages = {
        "com.example.paymentservice.repository",
        "com.ecommerce.common.outbox",
        "com.ecommerce.common.idempotency"
})
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
