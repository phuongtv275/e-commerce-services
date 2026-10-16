package com.example.apigateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "management.health.redis.enabled=false",
        "spring.cloud.config.enabled=false"
})
class ApiGatewayNegativeTests {

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        this.webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    @DisplayName("Negative: Protected route without Authorization header should return 401 AUTHENTICATION_REQUIRED")
    void testProtectedRouteWithoutTokenReturns401() {
        webTestClient.get().uri("/api/v1/orders")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("AUTHENTICATION_REQUIRED");
    }

    @Test
    @DisplayName("Negative: Protected route with malformed Authorization header should return 401")
    void testMalformedAuthorizationHeaderReturns401() {
        webTestClient.get().uri("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Basic invalid-token-format")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("AUTHENTICATION_REQUIRED");
    }

    @Test
    @DisplayName("Negative: Non-existent route on unmapped path without token should return 401")
    void testNonExistentUnmappedRouteReturns401() {
        webTestClient.get().uri("/non-existent-api-path")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("AUTHENTICATION_REQUIRED");
    }
}
