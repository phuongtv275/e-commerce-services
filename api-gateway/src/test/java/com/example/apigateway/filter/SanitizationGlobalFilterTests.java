package com.example.apigateway.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SanitizationGlobalFilterTests {

    private final SanitizationGlobalFilter filter = new SanitizationGlobalFilter();

    @Test
    @DisplayName("Filter order should be HIGHEST_PRECEDENCE")
    void testFilterOrder() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE, filter.getOrder());
    }

    @Test
    @DisplayName("Should strip all spoofed identity and internal assertion headers from incoming request")
    void shouldStripSpoofedHeaders() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/catalog/products")
                .header("X-User-Id", "attacker-admin")
                .header("X-User-Role", "ROLE_ADMINISTRATOR")
                .header("X-User-Roles", "ROLE_ADMINISTRATOR,ROLE_CUSTOMER")
                .header("X-User-Email", "attacker@evil.com")
                .header("X-Gateway-Assertion", "fake.assertion.token")
                .header("X-Internal-Service-Key", "stolen-internal-key")
                .header("X-Calling-Service", "evil-service")
                .header("X-Forwarded-For", "203.0.113.195")
                .header("X-Trusted-Client-IP", "1.2.3.4")
                .header("X-Custom-Allowed-Header", "allowed-value")
                .remoteAddress(new java.net.InetSocketAddress("192.168.1.100", 54321))
                .build();

        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        WebFilterChain chain = mock(WebFilterChain.class);
        when(chain.filter(any())).thenAnswer(invocation -> {
            org.springframework.web.server.ServerWebExchange mutatedExchange = invocation.getArgument(0);
            var headers = mutatedExchange.getRequest().getHeaders();

            assertNull(headers.getFirst("X-User-Id"));
            assertNull(headers.getFirst("X-User-Role"));
            assertNull(headers.getFirst("X-User-Roles"));
            assertNull(headers.getFirst("X-User-Email"));
            assertNull(headers.getFirst("X-Gateway-Assertion"));
            assertNull(headers.getFirst("X-Internal-Service-Key"));
            assertNull(headers.getFirst("X-Calling-Service"));
            assertNull(headers.getFirst("X-Forwarded-For"));

            // Must set gateway-owned X-Trusted-Client-IP from socket remote address
            assertEquals("192.168.1.100", headers.getFirst("X-Trusted-Client-IP"));

            assertEquals("allowed-value", headers.getFirst("X-Custom-Allowed-Header"));

            return Mono.empty();
        });

        filter.filter(exchange, chain).block();

        verify(chain, times(1)).filter(any());
    }
}
