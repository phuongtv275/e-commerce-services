package com.ecommerce.common.response;

import com.ecommerce.common.constant.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    private String code;
    private String message;
    private T data;
    private String correlationId;
    private Instant timestamp;
    private List<ErrorDetail> details;

    public static <T> ApiResponse<T> success(T data) {
        return success("Thao tác thành công", data);
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .code(ErrorCode.SUCCESS.name())
                .message(message)
                .data(data)
                .correlationId(getCorrelationIdFromMdc())
                .timestamp(Instant.now())
                .build();
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode) {
        return error(errorCode, errorCode.getDefaultMessage(), null);
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message) {
        return error(errorCode, message, null);
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message, List<ErrorDetail> details) {
        return ApiResponse.<T>builder()
                .code(errorCode.name())
                .message(message != null ? message : errorCode.getDefaultMessage())
                .details(details)
                .correlationId(getCorrelationIdFromMdc())
                .timestamp(Instant.now())
                .build();
    }

    private static String getCorrelationIdFromMdc() {
        return MDC.get("correlationId");
    }
}
