package com.ecommerce.common.filter;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.constant.SecurityConstants;
import com.ecommerce.common.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
public class InternalServiceFilter extends OncePerRequestFilter {

    private final Map<String, String> internalServiceKeys;
    private final ObjectMapper objectMapper;

    public InternalServiceFilter(Map<String, String> internalServiceKeys) {
        this(internalServiceKeys, createDefaultObjectMapper());
    }

    private static ObjectMapper createDefaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        return mapper;
    }

    public InternalServiceFilter(Map<String, String> internalServiceKeys, ObjectMapper objectMapper) {
        this.internalServiceKeys = internalServiceKeys != null ? internalServiceKeys : Collections.emptyMap();
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Chỉ áp dụng filter này cho các endpoint nội bộ /internal/**
        String path = request.getRequestURI();
        return !path.startsWith("/internal/") && !path.equals("/internal");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // Tuyệt đối không cho phép dùng X-Gateway-Assertion trên endpoint nội bộ /internal/**
        if (StringUtils.hasText(request.getHeader(SecurityConstants.HEADER_GATEWAY_ASSERTION))) {
            log.warn("Blocked attempt to pass X-Gateway-Assertion on internal endpoint: {}", request.getRequestURI());
            sendErrorResponse(response, HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED,
                    "Không được phép sử dụng Gateway Assertion trên endpoint nội bộ");
            return;
        }

        String callingService = request.getHeader(SecurityConstants.HEADER_CALLING_SERVICE);
        String serviceKey = request.getHeader(SecurityConstants.HEADER_INTERNAL_SERVICE_KEY);

        if (!StringUtils.hasText(callingService) || !StringUtils.hasText(serviceKey)) {
            log.warn("Missing internal service authentication headers on {}", request.getRequestURI());
            sendErrorResponse(response, HttpStatus.UNAUTHORIZED, ErrorCode.AUTHENTICATION_REQUIRED,
                    "Thiếu thông tin nhận diện dịch vụ nội bộ (X-Calling-Service hoặc X-Internal-Service-Key)");
            return;
        }

        String expectedKey = internalServiceKeys.get(callingService);
        if (expectedKey == null || !expectedKey.equals(serviceKey)) {
            log.warn("Invalid internal service key for caller {} on {}", callingService, request.getRequestURI());
            sendErrorResponse(response, HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED,
                    "Khóa dịch vụ nội bộ không hợp lệ");
            return;
        }

        // Cấp scoped role cụ thể theo caller: ví dụ order-service -> ROLE_SERVICE_ORDER
        String sanitizedCaller = callingService.replace("-", "_").toUpperCase();
        if (sanitizedCaller.endsWith("_SERVICE")) {
            sanitizedCaller = sanitizedCaller.substring(0, sanitizedCaller.length() - "_SERVICE".length());
        }
        String scopedRole = "ROLE_SERVICE_" + sanitizedCaller;

        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                callingService,
                null,
                List.of(new SimpleGrantedAuthority(scopedRole))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);

        filterChain.doFilter(request, response);
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
