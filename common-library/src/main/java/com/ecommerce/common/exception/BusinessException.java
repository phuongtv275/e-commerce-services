package com.ecommerce.common.exception;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.response.ErrorDetail;
import lombok.Getter;

import java.util.List;

@Getter
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;
    private final List<ErrorDetail> details;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
        this.details = null;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.details = null;
    }

    public BusinessException(ErrorCode errorCode, String message, List<ErrorDetail> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }
}
