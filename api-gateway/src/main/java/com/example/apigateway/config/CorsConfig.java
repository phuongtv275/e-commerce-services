package com.example.apigateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Cấu hình CORS tuân thủ chuẩn W3C CORS Spec khi bật allowCredentials.
 *
 * Tuyệt đối không dùng ký tự đại diện '*' cho allowedOrigins hoặc allowedHeaders
 * khi allowCredentials = true để tránh vi phạm bảo mật trình duyệt.
 */
@Configuration
public class CorsConfig {

    private final List<String> allowedOrigins;

    public CorsConfig(@Value("${cors.allowed-origins:http://localhost:3000,http://localhost:8080}") Object origins) {
        if (origins instanceof List<?>) {
            @SuppressWarnings("unchecked")
            List<String> list = (List<String>) origins;
            this.allowedOrigins = list;
        } else if (origins instanceof String str) {
            this.allowedOrigins = Arrays.stream(str.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        } else {
            this.allowedOrigins = List.of("http://localhost:3000");
        }
    }

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "X-Correlation-Id",
                "Idempotency-Key",
                "Accept",
                "Origin",
                "X-Requested-With",
                "X-Load-Test-Secret"
        ));
        config.setExposedHeaders(List.of(
                "X-Correlation-Id",
                "Retry-After"
        ));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}
