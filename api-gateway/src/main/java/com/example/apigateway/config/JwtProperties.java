package com.example.apigateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Bind toàn bộ cấu hình jwt.* từ application.yaml vào class này.
 * Sử dụng @ConfigurationProperties thay vì @Value để dễ quản lý và mở rộng.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /** Secret key (hex/base64-encoded hoặc raw string) dùng để verify chữ ký JWT — phải giống với identity-service */
    private String secretKey;

    /**
     * Định dạng của secret key: "base64" (mặc định) hoặc "raw".
     * Hỗ trợ chỉ định rõ ràng qua cấu hình hoặc prefix "raw:" / "base64:".
     */
    private String secretFormat = "base64";

    /**
     * Danh sách các path pattern (AntPath) được bỏ qua kiểm tra JWT.
     * Ví dụ: /identity/api/v1/auth/** cho phép login/register không cần token.
     */
    private List<String> whitelistPaths;
}
