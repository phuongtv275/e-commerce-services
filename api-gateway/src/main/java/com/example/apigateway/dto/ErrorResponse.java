package com.example.apigateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * DTO chuẩn cho response lỗi tại Gateway.
 * Gateway dùng WebFlux (reactive) nên không thể dùng @ControllerAdvice thông thường —
 * thay vào đó filter tự ghi response JSON thông qua ServerHttpResponse.
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private int status;
    private String error;
    private String message;
    private String path;

    @Builder.Default
    private String timestamp = Instant.now().toString();
}
