package com.example.apigateway.config;

import com.example.apigateway.filter.SecurityHeadersFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.DefaultCorsProcessor;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CorsAndSecurityHeadersTests {

    @Test
    @DisplayName("CORS Preflight request from allowed origin with credentials should succeed with 200 OK")
    void corsPreflightAllowedOriginWithCredentials() {
        CorsConfig corsConfig = new CorsConfig(List.of("http://localhost:3000", "http://localhost:8080"));
        CorsWebFilter corsWebFilter = corsConfig.corsWebFilter();

        MockServerHttpRequest request = MockServerHttpRequest.options("http://localhost:8080/api/v1/orders")
                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization, Content-Type, X-Correlation-Id")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        WebFilterChain chain = mock(WebFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        corsWebFilter.filter(exchange, chain).block();

        System.out.println("RESPONSE STATUS: " + exchange.getResponse().getStatusCode());
        System.out.println("RESPONSE HEADERS: " + exchange.getResponse().getHeaders());

        // Trong WebFlux CorsWebFilter, preflight response trả về Mono.empty() với headers và status mặc định 200 OK khi commit
        assertTrue(exchange.getResponse().getStatusCode() == null || HttpStatus.OK.equals(exchange.getResponse().getStatusCode()));
        assertEquals("http://localhost:3000", exchange.getResponse().getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals("true", exchange.getResponse().getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        assertNotNull(exchange.getResponse().getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS));
        verifyNoInteractions(chain);
    }

    @Test
    @DisplayName("SecurityHeadersFilter should add standard security headers to every response")
    void securityHeadersFilterShouldAddHeaders() {
        SecurityHeadersFilter filter = new SecurityHeadersFilter();

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/catalog/products").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        WebFilterChain chain = mock(WebFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        HttpHeaders headers = exchange.getResponse().getHeaders();
        assertEquals("nosniff", headers.getFirst("X-Content-Type-Options"));
        assertEquals("DENY", headers.getFirst("X-Frame-Options"));
        assertEquals("1; mode=block", headers.getFirst("X-XSS-Protection"));
        assertEquals("max-age=31536000; includeSubDomains", headers.getFirst("Strict-Transport-Security"));
    }
}
