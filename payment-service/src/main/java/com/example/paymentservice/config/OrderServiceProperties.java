package com.example.paymentservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "services.order-service")
public class OrderServiceProperties {

    /**
     * Base URL of order-service internal endpoint
     */
    private String baseUrl = "http://order-service:8084";

    /**
     * Internal calling key for payment-service
     */
    private String internalKey = "";
}
