package com.ecommerce.common.filter;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.constant.SecurityConstants;
import com.ecommerce.common.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.PublicKey;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
public class TrustedHeaderAuthenticationFilter extends OncePerRequestFilter {

    private final String applicationName;
    private final PublicKey publicKey;
    private final ObjectMapper objectMapper;

    public TrustedHeaderAuthenticationFilter(String applicationName, PublicKey publicKey) {
        this(applicationName, publicKey, createDefaultObjectMapper());
    }

    private static ObjectMapper createDefaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        return mapper;
    }

    public TrustedHeaderAuthenticationFilter(String applicationName, PublicKey publicKey, ObjectMapper objectMapper) {
        this.applicationName = applicationName;
        this.publicKey = publicKey;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String assertion = request.getHeader(SecurityConstants.HEADER_GATEWAY_ASSERTION);

        if (!StringUtils.hasText(assertion)) {
            // Không có assertion header, để các filter tiếp theo xử lý (cho phép anonymous trên public endpoints)
            filterChain.doFilter(request, response);
            return;
        }

        if (publicKey == null) {
            log.error("GATEWAY_ASSERTION_PUBLIC_KEY is not configured on service {}", applicationName);
            sendErrorResponse(response, HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "Cấu hình xác thực Gateway chưa sẵn sàng");
            return;
        }

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(publicKey)
                    .build()
                    .parseSignedClaims(assertion)
                    .getPayload();

            // Kiểm tra Audience Binding (aud claim bắt buộc phải khớp chính xác và duy nhất service hiện tại)
            Set<String> audiences = claims.getAudience();
            if (audiences == null || audiences.size() != 1 || !audiences.contains(applicationName)) {
                log.warn("Audience binding validation failed! Token aud={}, expected single applicationName={}",
                        audiences, applicationName);
                sendErrorResponse(response, HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED,
                        "Mã xác thực không hợp lệ cho dịch vụ đích (Audience mismatch)");
                return;
            }

            String userId = claims.getSubject();
            List<?> rawRoles = claims.get("roles", List.class);
            Collection<SimpleGrantedAuthority> authorities = Collections.emptyList();

            if (rawRoles != null) {
                authorities = rawRoles.stream()
                        .map(Object::toString)
                        .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                        .map(SimpleGrantedAuthority::new)
                        .collect(Collectors.toList());
            }

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userId, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);

            filterChain.doFilter(request, response);

        } catch (ExpiredJwtException e) {
            log.warn("Gateway assertion token expired: {}", e.getMessage());
            sendErrorResponse(response, HttpStatus.UNAUTHORIZED, ErrorCode.AUTHENTICATION_REQUIRED, "Mã xác thực Gateway đã hết hạn");
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Invalid Gateway assertion token signature or payload: {}", e.getMessage());
            sendErrorResponse(response, HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED, "Chữ ký mã xác thực Gateway không hợp lệ");
        }
    }

    private void sendErrorResponse(HttpServletResponse response,
                                   HttpStatus status,
                                   ErrorCode errorCode,
                                   String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ApiResponse<Void> apiResponse = ApiResponse.error(errorCode, message);
        response.getWriter().write(objectMapper.writeValueAsString(apiResponse));
    }
}
