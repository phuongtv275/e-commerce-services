package com.example.apigateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayRoutesConfig {

    @Bean
    public RouteLocator customRouteLocator(
            RouteLocatorBuilder builder,
            @Value("${IDENTITY_SERVICE_URL:localhost:8081}") String identityUrl,
            @Value("${CATALOG_SERVICE_URL:catalog-service:8082}") String catalogUrl,
            @Value("${INVENTORY_SERVICE_URL:inventory-service:8083}") String inventoryUrl,
            @Value("${ORDER_SERVICE_URL:order-service:8084}") String orderUrl,
            @Value("${PAYMENT_SERVICE_URL:localhost:8085}") String paymentUrl,
            @Value("${NOTIFICATION_SERVICE_URL:notification-service:8086}") String notifUrl
    ) {
        String identityUri = identityUrl.startsWith("http") ? identityUrl : "http://" + identityUrl;
        String catalogUri = catalogUrl.startsWith("http") ? catalogUrl : "http://" + catalogUrl;
        String inventoryUri = inventoryUrl.startsWith("http") ? inventoryUrl : "http://" + inventoryUrl;
        String orderUri = orderUrl.startsWith("http") ? orderUrl : "http://" + orderUrl;
        String paymentUri = paymentUrl.startsWith("http") ? paymentUrl : "http://" + paymentUrl;
        String notifUri = notifUrl.startsWith("http") ? notifUrl : "http://" + notifUrl;

        return builder.routes()
                .route("identity-prefixed-routes", r -> r.path("/identity/**")
                        .filters(f -> f.rewritePath("/identity/(?<remaining>.*)", "/${remaining}"))
                        .metadata("targetService", "identity-service")
                        .uri(identityUri))
                .route("identity-public-auth", r -> r.path("/api/v1/auth/**")
                        .metadata("targetService", "identity-service")
                        .uri(identityUri))
                .route("identity-protected-users", r -> r.path("/api/v1/users/**", "/api/v1/users", "/api/v1/admin/users/**", "/api/v1/admin/users", "/api/v1/admin/audit-logs/**", "/api/v1/admin/audit-logs")
                        .metadata("targetService", "identity-service")
                        .uri(identityUri))
                .route("catalog-public", r -> r.path("/api/v1/catalog/products/**", "/api/v1/catalog/categories/**", "/api/v1/catalog/brands/**", "/api/v1/catalog/search/**")
                        .metadata("targetService", "catalog-service")
                        .uri(catalogUri))
                .route("catalog-admin", r -> r.path("/api/v1/catalog/admin/**")
                        .metadata("targetService", "catalog-service")
                        .uri(catalogUri))
                .route("inventory-service-routes", r -> r.path("/api/v1/inventory/**")
                        .metadata("targetService", "inventory-service")
                        .uri(inventoryUri))
                .route("order-service-routes", r -> r.path("/api/v1/orders/**")
                        .metadata("targetService", "order-service")
                        .uri(orderUri))
                .route("payment-momo-ipn-webhook", r -> r.path("/api/v1/payments/momo/ipn")
                        .metadata("targetService", "payment-service")
                        .uri(paymentUri))
                .route("payment-service-routes", r -> r.path("/api/v1/payments/**", "/api/v1/admin/payments/**")
                        .metadata("targetService", "payment-service")
                        .uri(paymentUri))
                .route("notification-admin-routes", r -> r.path("/api/v1/admin/notifications/**")
                        .metadata("targetService", "notification-service")
                        .uri(notifUri))
                .build();
    }
}
