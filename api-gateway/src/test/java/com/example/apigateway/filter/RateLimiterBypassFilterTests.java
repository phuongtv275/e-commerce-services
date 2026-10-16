package com.example.apigateway.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RateLimiterBypassFilterTests {

    private static final String SECRET = "super-secret-load-test-key-2026";
    private final RateLimiterBypassFilter filter = new RateLimiterBypassFilter(SECRET);

    @Test
    @DisplayName("Should bypass rate limiter for GET catalog products when valid secret is provided")
    void shouldBypassForCatalogGetWithValidSecret() {
        MockServerHttpRequest request = MockServerHttpRequest.method(HttpMethod.GET, "/api/v1/catalog/products/123")
                .header(RateLimiterBypassFilter.LOAD_TEST_HEADER, SECRET)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        WebFilterChain chain = mock(WebFilterChain.class);
        when(chain.filter(any())).thenAnswer(invocation -> {
            ServerWebExchange mutated = invocation.getArgument(0);
            // Header secret must be stripped before forwarding downstream
            assertNull(mutated.getRequest().getHeaders().getFirst(RateLimiterBypassFilter.LOAD_TEST_HEADER));
            // Attribute must be marked as bypassed
            assertTrue(Boolean.TRUE.equals(mutated.getAttribute(RateLimiterBypassFilter.BYPASS_ATTRIBUTE)));
            return Mono.empty();
        });

        filter.filter(exchange, chain).block();

        verify(chain, times(1)).filter(any());
    }

    @Test
    @DisplayName("Should NOT bypass rate limiter on non-whitelisted routes (e.g. POST /api/v1/orders/checkout)")
    void shouldNotBypassForSensitiveRoutesEvenWithSecret() {
        MockServerHttpRequest request = MockServerHttpRequest.method(HttpMethod.POST, "/api/v1/orders/checkout")
                .header(RateLimiterBypassFilter.LOAD_TEST_HEADER, SECRET)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        WebFilterChain chain = mock(WebFilterChain.class);
        when(chain.filter(any())).thenAnswer(invocation -> {
            ServerWebExchange mutated = invocation.getArgument(0);
            assertFalse(Boolean.TRUE.equals(mutated.getAttribute(RateLimiterBypassFilter.BYPASS_ATTRIBUTE)));
            return Mono.empty();
        });

        filter.filter(exchange, chain).block();

        verify(chain, times(1)).filter(any());
    }

    @Test
    @DisplayName("Should NOT bypass when secret is invalid")
    void shouldNotBypassWhenSecretIsInvalid() {
        MockServerHttpRequest request = MockServerHttpRequest.method(HttpMethod.GET, "/api/v1/catalog/search")
                .header(RateLimiterBypassFilter.LOAD_TEST_HEADER, "wrong-secret")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        WebFilterChain chain = mock(WebFilterChain.class);
        when(chain.filter(any())).thenAnswer(invocation -> {
            ServerWebExchange mutated = invocation.getArgument(0);
            assertFalse(Boolean.TRUE.equals(mutated.getAttribute(RateLimiterBypassFilter.BYPASS_ATTRIBUTE)));
            return Mono.empty();
        });

        filter.filter(exchange, chain).block();

        verify(chain, times(1)).filter(any());
    }
}
