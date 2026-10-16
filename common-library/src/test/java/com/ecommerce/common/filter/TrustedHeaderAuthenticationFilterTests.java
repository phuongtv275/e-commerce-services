package com.ecommerce.common.filter;

import com.ecommerce.common.constant.SecurityConstants;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TrustedHeaderAuthenticationFilterTests {

    private KeyPair keyPair;
    private TrustedHeaderAuthenticationFilter filter;
    private final String TARGET_SERVICE = "order-service";

    @BeforeEach
    void setUp() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        filter = new TrustedHeaderAuthenticationFilter(TARGET_SERVICE, keyPair.getPublic());
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Positive: Token hợp lệ đúng audience gán authentication vào SecurityContext")
    void testValidAssertion_Positive() throws ServletException, IOException {
        String token = Jwts.builder()
                .subject("user-uuid-123")
                .audience().add(TARGET_SERVICE).and()
                .claim("roles", List.of("CUSTOMER", "ADMINISTRATOR"))
                .expiration(Date.from(Instant.now().plus(60, ChronoUnit.SECONDS)))
                .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(SecurityConstants.HEADER_GATEWAY_ASSERTION, token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain filterChain = (req, res) -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            assertNotNull(auth);
            assertEquals("user-uuid-123", auth.getPrincipal());
            assertTrue(auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_CUSTOMER")));
            assertTrue(auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMINISTRATOR")));
        };

        filter.doFilter(request, response, filterChain);
        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("Negative: Audience mismatch (token của catalog-service phát lại sang order-service) bị chặn HTTP 403")
    void testAudienceMismatch_Negative() throws ServletException, IOException {
        String replayToken = Jwts.builder()
                .subject("user-uuid-123")
                .audience().add("catalog-service").and() // Token sinh cho catalog-service
                .claim("roles", List.of("CUSTOMER"))
                .expiration(Date.from(Instant.now().plus(60, ChronoUnit.SECONDS)))
                .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(SecurityConstants.HEADER_GATEWAY_ASSERTION, replayToken);
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain filterChain = (req, res) -> fail("Filter chain không được phép tiếp tục khi audience mismatch");

        filter.doFilter(request, response, filterChain);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("ACCESS_DENIED"));
        assertTrue(response.getContentAsString().contains("Audience mismatch"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("Negative: Token chứa nhiều audience (aud bao gồm cả service khác) bị từ chối với HTTP 403")
    void testMultipleAudiencesRejected_Negative() throws ServletException, IOException {
        String multiAudToken = Jwts.builder()
                .subject("user-uuid-123")
                .audience().add(TARGET_SERVICE).add("catalog-service").and() // 2 audiences
                .claim("roles", List.of("CUSTOMER"))
                .expiration(Date.from(Instant.now().plus(60, ChronoUnit.SECONDS)))
                .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(SecurityConstants.HEADER_GATEWAY_ASSERTION, multiAudToken);
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain filterChain = (req, res) -> fail("Filter chain không được phép tiếp tục khi có nhiều audience");

        filter.doFilter(request, response, filterChain);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("ACCESS_DENIED"));
        assertTrue(response.getContentAsString().contains("Audience mismatch"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("Negative: Token hết hạn bị từ chối với HTTP 401")
    void testExpiredToken_Negative() throws ServletException, IOException {
        String expiredToken = Jwts.builder()
                .subject("user-uuid-123")
                .audience().add(TARGET_SERVICE).and()
                .claim("roles", List.of("CUSTOMER"))
                .expiration(Date.from(Instant.now().minus(60, ChronoUnit.SECONDS))) // Đã hết hạn
                .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(SecurityConstants.HEADER_GATEWAY_ASSERTION, expiredToken);
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain filterChain = (req, res) -> fail("Filter chain không được phép tiếp tục khi token hết hạn");

        filter.doFilter(request, response, filterChain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("AUTHENTICATION_REQUIRED"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("Negative: Chữ ký không hợp lệ bị từ chối với HTTP 403")
    void testInvalidSignature_Negative() throws Exception {
        // Ký bằng cặp khóa RSA khác
        KeyPair otherKeyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        String tamperedToken = Jwts.builder()
                .subject("user-uuid-123")
                .audience().add(TARGET_SERVICE).and()
                .signWith(otherKeyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(SecurityConstants.HEADER_GATEWAY_ASSERTION, tamperedToken);
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain filterChain = (req, res) -> fail("Filter chain không được phép tiếp tục khi sai chữ ký");

        filter.doFilter(request, response, filterChain);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("ACCESS_DENIED"));
    }
}
