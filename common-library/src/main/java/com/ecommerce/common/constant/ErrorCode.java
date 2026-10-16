package com.ecommerce.common.constant;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {
    SUCCESS(HttpStatus.OK, "Thao tác thành công"),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Dữ liệu yêu cầu không hợp lệ"),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "Yêu cầu xác thực danh tính"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Không có quyền truy cập tài nguyên"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy tài nguyên yêu cầu"),
    STATE_CONFLICT(HttpStatus.CONFLICT, "Xung đột trạng thái tài nguyên"),
    INVENTORY_INSUFFICIENT(HttpStatus.CONFLICT, "Số lượng tồn kho không đủ để đáp ứng"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "Khóa Idempotency đã được sử dụng với payload khác"),
    BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY, "Vi phạm quy tắc nghiệp vụ hệ thống"),
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "Tần suất yêu cầu vượt quá giới hạn cho phép"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Lỗi máy chủ nội bộ"),
    UPSTREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Dịch vụ phụ thuộc hiện không khả dụng"),
    UPSTREAM_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "Quá thời gian phản hồi từ dịch vụ phụ thuộc");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }
}
