package com.example.apigateway.filter;

import com.example.apigateway.config.JwtProperties;
import com.example.apigateway.security.GatewayAssertionSigner;
import com.example.apigateway.security.JwtUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class JwtAuthFilterTests {

    private JwtUtils jwtUtils;
    private JwtProperties jwtProperties;
    private ObjectMapper objectMapper;
    private ReactiveStringRedisTemplate redisTemplate;
    private GatewayAssertionSigner assertionSigner;
    private JwtAuthFilter filter;
    private WebFilterChain chain;

    @BeforeEach
    void setUp() {
        jwtUtils = mock(JwtUtils.class);
        jwtProperties = new JwtProperties();
        jwtProperties.setWhitelistPaths(List.of(
                "/api/v1/auth/**",
                "/api/v1/catalog/products/**",
                "/actuator/**"
        ));
        objectMapper = new ObjectMapper();
        redisTemplate = mock(ReactiveStringRedisTemplate.class);
        assertionSigner = mock(GatewayAssertionSigner.class);

        filter = new JwtAuthFilter(jwtUtils, jwtProperties, objectMapper, redisTemplate, assertionSigner);
        chain = mock(WebFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("Whitelisted path should bypass authentication and set correlation ID")
    void testWhitelistedPathBypassesAuth() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/auth/login").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain).block();

        verify(chain, times(1)).filter(any());
        verifyNoInteractions(jwtUtils);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("Protected path with missing Authorization header should return 401")
    void testMissingAuthHeaderReturns401() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/orders").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        verifyNoInteractions(chain);
    }

    @Test
    @DisplayName("Protected path with expired token should return 401")
    void testExpiredTokenReturns401() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer expired-token")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        when(jwtUtils.parseToken("expired-token"))
                .thenThrow(new ExpiredJwtException(null, null, "Token has expired"));

        filter.filter(exchange, chain).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        verifyNoInteractions(chain);
    }

    @Test
    @DisplayName("Protected path with invalid signature should return 401")
    void testInvalidSignatureReturns401() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer tampered-token")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        when(jwtUtils.parseToken("tampered-token"))
                .thenThrow(new SignatureException("Invalid JWT signature"));

        filter.filter(exchange, chain).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        verifyNoInteractions(chain);
    }

    @Test
    @DisplayName("Protected path with blacklisted token should return 401")
    void testBlacklistedTokenReturns401() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("user-1");
        when(claims.getId()).thenReturn("jti-123");
        when(claims.get("roles", List.class)).thenReturn(List.of("ROLE_CUSTOMER"));
        when(jwtUtils.parseToken("valid-token")).thenReturn(claims);

        when(redisTemplate.hasKey("blacklist:jti-123")).thenReturn(Mono.just(true));

        filter.filter(exchange, chain).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        verifyNoInteractions(chain);
    }

    @Test
    @DisplayName("Protected path with valid token should set downstream headers and assertion")
    void testValidTokenSetsHeadersAndAssertion() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("user-42");
        when(claims.getId()).thenReturn("jti-42");
        when(claims.get("roles", List.class)).thenReturn(List.of("ROLE_CUSTOMER", "ROLE_ADMINISTRATOR"));
        when(jwtUtils.parseToken("valid-token")).thenReturn(claims);

        when(redisTemplate.hasKey("blacklist:jti-42")).thenReturn(Mono.just(false));
        when(assertionSigner.signAssertion("user-42", List.of("ROLE_CUSTOMER", "ROLE_ADMINISTRATOR"), "order-service"))
                .thenReturn("signed-rsa-assertion");

        when(chain.filter(any())).thenAnswer(invocation -> {
            ServerWebExchange mutated = invocation.getArgument(0);
            var headers = mutated.getRequest().getHeaders();

            assertEquals("user-42", headers.getFirst("X-User-Id"));
            assertEquals("ROLE_ADMINISTRATOR", headers.getFirst("X-User-Role"));
            assertEquals("ROLE_CUSTOMER,ROLE_ADMINISTRATOR", headers.getFirst("X-User-Roles"));
            assertEquals("signed-rsa-assertion", headers.getFirst("X-Gateway-Assertion"));
            assertNotNull(headers.getFirst("X-Correlation-Id"));

            return Mono.empty();
        });

        filter.filter(exchange, chain).block();

        verify(chain, times(1)).filter(any());
    }
}
