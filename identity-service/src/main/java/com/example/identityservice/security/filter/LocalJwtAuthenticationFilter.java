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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class LocalJwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;
    private final RedisBlacklistService redisBlacklistService;
    private final MyUserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String gatewayAssertion = request.getHeader(SecurityConstants.HEADER_GATEWAY_ASSERTION);

        // Fallback filter only activates when request does NOT contain X-Gateway-Assertion
        if (!StringUtils.hasText(gatewayAssertion)) {
            String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

            if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7).trim();

                if (StringUtils.hasText(token) && jwtUtils.validateToken(token)) {
                    Claims claims = jwtUtils.extractAllClaims(token);
                    String jti = claims.getId();

                    boolean isBlacklisted = false;
                    try {
                        isBlacklisted = jti != null && redisBlacklistService.isBlacklisted(jti);
                    } catch (Exception e) {
                        log.warn("Failed to check Redis blacklist for jti {}: {}", jti, e.getMessage());
                    }

                    if (isBlacklisted) {
                        log.warn("Bearer token has been revoked / blacklisted (jti={})", jti);
                    } else if (SecurityContextHolder.getContext().getAuthentication() == null) {
                        String username = claims.get("email", String.class);
                        if (!StringUtils.hasText(username)) {
                            username = claims.getSubject();
                        }

                        List<?> rawRoles = claims.get("roles", List.class);
                        List<SimpleGrantedAuthority> authorities = Collections.emptyList();
                        if (rawRoles != null) {
                            authorities = rawRoles.stream()
                                    .map(Object::toString)
                                    .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                                    .map(SimpleGrantedAuthority::new)
                                    .toList();
                        }

                        UserDetails userDetails = null;
                        if (userDetailsService != null && StringUtils.hasText(username)) {
                            try {
                                userDetails = userDetailsService.loadUserByUsername(username);
                            } catch (Exception e) {
                                log.debug("UserDetailsService could not find username {}, falling back to claims: {}",
                                        username, e.getMessage());
                            }
                        }

                        if (userDetails == null) {
                            UUID userId = null;
                            try {
                                userId = UUID.fromString(claims.getSubject());
                            } catch (IllegalArgumentException ignored) {
                            }
                            User fallbackUser = User.builder()
                                    .id(userId)
                                    .email(username)
                                    .fullName(username)
                                    .build();
                            userDetails = MyUserDetails.builder()
                                    .user(fallbackUser)
                                    .authorities(authorities)
                                    .build();
                        }

                        UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                        log.debug("Successfully authenticated local request for user: {}", username);
                    }
                }
            }
        }

        filterChain.doFilter(request, response);
    }
}
