package com.example.apigateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RateLimiterConfigTests {

    private final RateLimiterConfig config = new RateLimiterConfig();

    @Test
    @DisplayName("ipKeyResolver should resolve client IP from X-Forwarded-For header")
    void ipKeyResolverShouldUseXForwardedFor() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/catalog/products")
                .header("X-Forwarded-For", "203.0.113.195, 70.41.3.18")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        String resolvedKey = config.ipKeyResolver().resolve(exchange).block();
        assertEquals("203.0.113.195", resolvedKey);
    }

    @Test
    @DisplayName("ipKeyResolver should fallback to remote address if X-Forwarded-For is missing")
    void ipKeyResolverShouldFallbackToRemoteAddress() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/catalog/products")
                .remoteAddress(new InetSocketAddress("192.168.1.100", 50000))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        String resolvedKey = config.ipKeyResolver().resolve(exchange).block();
        assertEquals("192.168.1.100", resolvedKey);
    }

    @Test
    @DisplayName("userKeyResolver should resolve user ID with 'user:' prefix when X-User-Id is present")
    void userKeyResolverShouldUseUserId() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/orders")
                .header("X-User-Id", "customer-99")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        String resolvedKey = config.userKeyResolver().resolve(exchange).block();
        assertEquals("user:customer-99", resolvedKey);
    }

    @Test
    @DisplayName("userKeyResolver should fallback to ipKeyResolver when X-User-Id is missing")
    void userKeyResolverShouldFallbackToIp() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/orders")
                .remoteAddress(new InetSocketAddress("10.0.0.5", 40000))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        String resolvedKey = config.userKeyResolver().resolve(exchange).block();
        assertEquals("10.0.0.5", resolvedKey);
    }

    @Test
    @DisplayName("KeyResolvers should return bypass key when RATE_LIMITER_BYPASS attribute is set")
    void keyResolversShouldBypassWhenAttributeIsSet() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/catalog/products")
                .remoteAddress(new InetSocketAddress("10.0.0.5", 40000))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put("RATE_LIMITER_BYPASS", true);

        String ipKey = config.ipKeyResolver().resolve(exchange).block();
        String userKey = config.userKeyResolver().resolve(exchange).block();

        assertNotNull(ipKey);
        assertNotNull(userKey);
        org.junit.jupiter.api.Assertions.assertTrue(ipKey.startsWith("bypass:"));
        org.junit.jupiter.api.Assertions.assertTrue(userKey.startsWith("bypass:"));
    }
}
