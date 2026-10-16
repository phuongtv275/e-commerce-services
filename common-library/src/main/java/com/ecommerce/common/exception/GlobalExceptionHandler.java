package com.ecommerce.common.exception;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.response.ApiResponse;
import com.ecommerce.common.response.ErrorDetail;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        log.warn("BusinessException: code={}, message={}, correlationId={}",
                ex.getErrorCode(), ex.getMessage(), MDC.get("correlationId"));
        ErrorCode ec = ex.getErrorCode();
        ApiResponse<Void> body = ApiResponse.error(ec, ex.getMessage(), ex.getDetails());
        return ResponseEntity.status(ec.getHttpStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException ex) {
        List<ErrorDetail> details = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            details.add(ErrorDetail.builder()
                    .field(fe.getField())
                    .rejectedValue(fe.getRejectedValue())
                    .message(fe.getDefaultMessage())
                    .build());
        }
        log.warn("Validation failed on request with {} errors, correlationId={}",
                details.size(), MDC.get("correlationId"));
        ApiResponse<Void> body = ApiResponse.error(ErrorCode.VALIDATION_ERROR, "Dữ liệu yêu cầu không hợp lệ", details);
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.getHttpStatus()).body(body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        List<ErrorDetail> details = new ArrayList<>();
        for (ConstraintViolation<?> cv : ex.getConstraintViolations()) {
            details.add(ErrorDetail.builder()
                    .field(cv.getPropertyPath().toString())
                    .rejectedValue(cv.getInvalidValue())
                    .message(cv.getMessage())
                    .build());
        }
        log.warn("Constraint violation: {} errors, correlationId={}", details.size(), MDC.get("correlationId"));
        ApiResponse<Void> body = ApiResponse.error(ErrorCode.VALIDATION_ERROR, "Vi phạm ràng buộc dữ liệu", details);
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.getHttpStatus()).body(body);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(AccessDeniedException ex) {
        log.warn("Access denied: {}, correlationId={}", ex.getMessage(), MDC.get("correlationId"));
        ApiResponse<Void> body = ApiResponse.error(ErrorCode.ACCESS_DENIED, "Không có quyền truy cập tài nguyên");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthenticationException(AuthenticationException ex) {
        log.warn("Authentication required: {}, correlationId={}", ex.getMessage(), MDC.get("correlationId"));
        ApiResponse<Void> body = ApiResponse.error(ErrorCode.AUTHENTICATION_REQUIRED, "Yêu cầu xác thực danh tính");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneralException(Exception ex) {
        log.error("Unhandled exception caught by GlobalExceptionHandler, correlationId={}",
                MDC.get("correlationId"), ex);
        // Bảo vệ an toàn: che giấu stack trace nhạy cảm ở production
        ApiResponse<Void> body = ApiResponse.error(ErrorCode.INTERNAL_ERROR, "Đã xảy ra lỗi nội bộ hệ thống. Vui lòng thử lại sau.");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
