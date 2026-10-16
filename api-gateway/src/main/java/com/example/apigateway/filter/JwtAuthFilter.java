package com.example.apigateway.filter;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.response.ApiResponse;
import com.example.apigateway.config.JwtProperties;
import com.example.apigateway.security.GatewayAssertionSigner;
import com.example.apigateway.security.JwtUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * WebFilter xác thực JWT cho tất cả protected request đi qua API Gateway.
 *
 * Các nhiệm vụ cốt lõi:
 * 1. Đảm bảo request luôn có mã vết tương quan (X-Correlation-Id).
 * 2. Kiểm tra danh sách đường dẫn công khai (whitelistPaths) -> bỏ qua xác thực nếu khớp.
 * 3. Kiểm tra định dạng Authorization Header (Bearer <token>).
 * 4. Xác minh tính hợp lệ của token khách hàng (HMAC chữ ký và thời hạn).
 * 5. Truy vấn danh sách thu hồi token trên Redis (Token Blacklist: blacklist:{jti}).
 * 6. Ký khẳng định danh tính bất đối xứng (X-Gateway-Assertion) sử dụng RSA-2048
 *    có audience binding đích danh tới microservice nhận.
 * 7. Gán context headers an toàn (X-User-Id, X-User-Role, X-User-Roles, X-Gateway-Assertion).
 * 8. Chuẩn hóa phản hồi lỗi bằng ApiResponse<Void> theo chuẩn chung toàn hệ thống.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter implements WebFilter, Ordered {

    public static final String BLACKLIST_KEY_PREFIX = "blacklist:";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private final JwtUtils jwtUtils;
    private final JwtProperties jwtProperties;
    private final ObjectMapper objectMapper;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final GatewayAssertionSigner assertionSigner;
    private final AntPathMatcher antPathMatcher = new AntPathMatcher();

    /**
     * Chạy ngay sau SanitizationGlobalFilter (Ordered.HIGHEST_PRECEDENCE)
     * để đảm bảo header giả mạo đã bị loại bỏ trước khi Gateway gán identity thật.
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        // Bảo lưu correlationId nếu client/proxy gửi lên, hoặc tạo mới UUID
        String incomingCorrId = request.getHeaders().getFirst(CORRELATION_ID_HEADER);
        String correlationId = (incomingCorrId != null && !incomingCorrId.isBlank())
                ? incomingCorrId.trim()
                : UUID.randomUUID().toString();

        log.debug("[{}] Incoming request: {} {}", correlationId, request.getMethod(), path);

        // 1. Kiểm tra Whitelist
        if (isWhitelisted(path)) {
            log.debug("[{}] Path '{}' is whitelisted, skipping JWT validation", correlationId, path);
            ServerHttpRequest mutatedRequest = request.mutate()
                    .header(CORRELATION_ID_HEADER, correlationId)
                    .build();
            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        }

        // 2. Lấy Authorization Header
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            log.warn("[{}] Missing or malformed Authorization header for path: {}", correlationId, path);
            return writeErrorResponse(
                    exchange,
                    ErrorCode.AUTHENTICATION_REQUIRED,
                    "Authorization header missing or malformed. Expected format: Bearer <token>",
                    correlationId
            );
        }

        // 3. Tách token
        String token = authHeader.substring(BEARER_PREFIX.length()).trim();

        try {
            // 4. Parse và verify JWT token
            var claims = jwtUtils.parseToken(token);
            String username = claims.getSubject();
            String jti = claims.getId();

            if (username == null || username.isBlank()) {
                log.warn("[{}] Token missing 'sub' (username) claim for path: {}", correlationId, path);
                return writeErrorResponse(exchange, ErrorCode.AUTHENTICATION_REQUIRED, "Invalid token: missing subject", correlationId);
            }

            if (jti == null || jti.isBlank()) {
                log.warn("[{}] Token missing 'jti' claim for path: {}", correlationId, path);
                return writeErrorResponse(exchange, ErrorCode.AUTHENTICATION_REQUIRED, "Invalid token: missing jti claim", correlationId);
            }

            @SuppressWarnings("unchecked")
            List<String> roles = claims.get("roles", List.class);
            String primaryRole = (roles != null && !roles.isEmpty())
                    ? roles.stream().filter(r -> r.equals("ROLE_ADMINISTRATOR") || r.equals("ROLE_ADMIN")).findFirst().orElse(roles.get(0))
                    : "";

            // 5. Kiểm tra Redis Blacklist (Reactive, Non-blocking)
            String blacklistKey = BLACKLIST_KEY_PREFIX + jti;
            return redisTemplate.hasKey(blacklistKey)
                    .timeout(Duration.ofMillis(2000))
                    .defaultIfEmpty(false)
                    .onErrorResume(ex -> {
                        log.error("[{}] Error querying Redis blacklist for jti '{}': {}", correlationId, jti, ex.getMessage());
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.INTERNAL_SERVER_ERROR,
                                "Authentication service temporarily unavailable"
                        ));
                    })
                    .flatMap(isBlacklisted -> {
                        if (Boolean.TRUE.equals(isBlacklisted)) {
                            log.warn("[{}] Token with jti '{}' is revoked for user '{}'", correlationId, jti, username);
                            return writeErrorResponse(
                                    exchange,
                                    ErrorCode.AUTHENTICATION_REQUIRED,
                                    "Token has been revoked",
                                    correlationId
                            );
                        }

                        // 6. Phân giải target audience và ký Assertion RSA
                        String targetService = resolveTargetService(path);
                        String assertionToken = assertionSigner.signAssertion(username, roles, targetService);

                        // 7. Gán headers cho downstream request
                        ServerHttpRequest.Builder reqBuilder = request.mutate()
                                .header(CORRELATION_ID_HEADER, correlationId)
                                .header("X-User-Id", username)
                                .header("X-User-Role", primaryRole)
                                .header("X-User-Roles", String.join(",", roles != null ? roles : List.of()));

                        if (assertionToken != null) {
                            reqBuilder.header("X-Gateway-Assertion", assertionToken);
                        }

                        return chain.filter(exchange.mutate().request(reqBuilder.build()).build());
                    })
                    .onErrorResume(ResponseStatusException.class, ex ->
                            writeErrorResponse(exchange, ErrorCode.INTERNAL_ERROR, ex.getReason(), correlationId)
                    );

        } catch (ExpiredJwtException ex) {
            log.warn("[{}] Expired JWT for path '{}': {}", correlationId, path, ex.getMessage());
            return writeErrorResponse(exchange, ErrorCode.AUTHENTICATION_REQUIRED, "Token has expired", correlationId);

        } catch (JwtException ex) {
            log.warn("[{}] Invalid JWT for path '{}': {}", correlationId, path, ex.getMessage());
            return writeErrorResponse(exchange, ErrorCode.AUTHENTICATION_REQUIRED, "Invalid token signature or format", correlationId);

        } catch (IllegalArgumentException ex) {
            log.warn("[{}] Empty/null JWT for path '{}': {}", correlationId, path, ex.getMessage());
            return writeErrorResponse(exchange, ErrorCode.AUTHENTICATION_REQUIRED, "Token must not be null or empty", correlationId);
        }
    }

    private boolean isWhitelisted(String path) {
        List<String> whitelistPaths = jwtProperties.getWhitelistPaths();
        if (whitelistPaths == null || whitelistPaths.isEmpty()) {
            return false;
        }
        return whitelistPaths.stream().anyMatch(pattern -> antPathMatcher.match(pattern, path));
    }

    private String resolveTargetService(String path) {
        if (path.startsWith("/api/v1/auth") || path.startsWith("/api/v1/users") || path.startsWith("/api/v1/admin/users") || path.startsWith("/identity")) {
            return "identity-service";
        } else if (path.startsWith("/api/v1/catalog") || path.startsWith("/product") || path.startsWith("/api/products")) {
            return "catalog-service";
        } else if (path.startsWith("/api/v1/inventory")) {
            return "inventory-service";
        } else if (path.startsWith("/api/v1/orders")) {
            return "order-service";
        } else if (path.startsWith("/api/v1/payments")) {
            return "payment-service";
        } else if (path.startsWith("/api/v1/admin/notifications")) {
            return "notification-service";
        } else if (path.startsWith("/api/v1/admin/")) {
            String[] parts = path.split("/");
            if (parts.length >= 5) {
                return parts[4] + "-service";
            }
        }
        return "unknown-service";
    }

    private Mono<Void> writeErrorResponse(ServerWebExchange exchange,
                                          ErrorCode errorCode,
                                          String message,
                                          String correlationId) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        response.setStatusCode(errorCode.getHttpStatus());
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        ApiResponse<Void> apiResponse = ApiResponse.error(errorCode, message);
        apiResponse.setCorrelationId(correlationId);

        try {
            byte[] bytes = objectMapper.writeValueAsBytes(apiResponse);
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize error response: {}", ex.getMessage());
            return response.setComplete();
        }
    }
}
