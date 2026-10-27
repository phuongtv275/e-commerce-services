package com.example.identityservice.security.filter;

import com.ecommerce.common.constant.SecurityConstants;
import com.example.identityservice.models.entities.User;
import com.example.identityservice.models.services.RedisBlacklistService;
import com.example.identityservice.security.jwt.JwtUtils;
import com.example.identityservice.security.principal.MyUserDetails;
import com.example.identityservice.security.principal.MyUserDetailsService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LocalJwtAuthenticationFilterTests {

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private RedisBlacklistService redisBlacklistService;

    @Mock
    private MyUserDetailsService userDetailsService;

    @Mock
    private FilterChain filterChain;

    @InjectMocks
    private LocalJwtAuthenticationFilter filter;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Positive: Request without X-Gateway-Assertion and with valid Bearer token sets authentication")
    void testFilter_ValidBearerToken_Authenticates() throws ServletException, IOException {
        String token = "valid.jwt.token";
        request.addHeader("Authorization", "Bearer " + token);

        UUID userId = UUID.randomUUID();
        Claims claims = mock(Claims.class);
        when(claims.getId()).thenReturn("jti-12345");
        lenient().when(claims.getSubject()).thenReturn(userId.toString());
        when(claims.get("email", String.class)).thenReturn("user@example.com");
        doReturn(List.of("ROLE_CUSTOMER")).when(claims).get("roles", List.class);

        when(jwtUtils.validateToken(token)).thenReturn(true);
        when(jwtUtils.extractAllClaims(token)).thenReturn(claims);
        when(redisBlacklistService.isBlacklisted("jti-12345")).thenReturn(false);

        MyUserDetails userDetails = MyUserDetails.builder()
                .user(User.builder().id(userId).email("user@example.com").build())
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                .build();
        when(userDetailsService.loadUserByUsername("user@example.com")).thenReturn(userDetails);

        filter.doFilter(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getName()).isEqualTo("user@example.com");
        assertThat(auth.getAuthorities()).extracting("authority").contains("ROLE_CUSTOMER");

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Positive: Fallback when UserDetailsService fails still creates authentication from claims")
    void testFilter_UserDetailsServiceFails_FallbackToClaims() throws ServletException, IOException {
        String token = "valid.fallback.token";
        request.addHeader("Authorization", "Bearer " + token);

        UUID userId = UUID.randomUUID();
        Claims claims = mock(Claims.class);
        when(claims.getId()).thenReturn("jti-fallback");
        when(claims.getSubject()).thenReturn(userId.toString());
        when(claims.get("email", String.class)).thenReturn("fallback@example.com");
        doReturn(List.of("ROLE_ADMINISTRATOR")).when(claims).get("roles", List.class);

        when(jwtUtils.validateToken(token)).thenReturn(true);
        when(jwtUtils.extractAllClaims(token)).thenReturn(claims);
        when(redisBlacklistService.isBlacklisted("jti-fallback")).thenReturn(false);
        when(userDetailsService.loadUserByUsername("fallback@example.com")).thenThrow(new RuntimeException("DB unavailable"));

        filter.doFilter(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting("authority").contains("ROLE_ADMINISTRATOR");

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Negative: Request WITH X-Gateway-Assertion skips local JWT filter completely")
    void testFilter_WithGatewayAssertion_SkipsLocalFilter() throws ServletException, IOException {
        request.addHeader(SecurityConstants.HEADER_GATEWAY_ASSERTION, "gateway-assertion-jwt");
        request.addHeader("Authorization", "Bearer some.jwt.token");

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(jwtUtils);
        verifyNoInteractions(redisBlacklistService);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Negative: Blacklisted token does not set authentication")
    void testFilter_BlacklistedToken_DoesNotAuthenticate() throws ServletException, IOException {
        String token = "revoked.jwt.token";
        request.addHeader("Authorization", "Bearer " + token);

        Claims claims = mock(Claims.class);
        when(claims.getId()).thenReturn("jti-revoked");

        when(jwtUtils.validateToken(token)).thenReturn(true);
        when(jwtUtils.extractAllClaims(token)).thenReturn(claims);
        when(redisBlacklistService.isBlacklisted("jti-revoked")).thenReturn(true);

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Negative: Invalid JWT token does not set authentication")
    void testFilter_InvalidToken_DoesNotAuthenticate() throws ServletException, IOException {
        String token = "invalid.token";
        request.addHeader("Authorization", "Bearer " + token);

        when(jwtUtils.validateToken(token)).thenReturn(false);

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Negative: Request without Authorization header passes through without authentication")
    void testFilter_NoAuthHeader_PassesThrough() throws ServletException, IOException {
        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(jwtUtils);
        verify(filterChain).doFilter(request, response);
    }
}
