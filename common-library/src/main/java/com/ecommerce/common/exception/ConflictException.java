package com.ecommerce.common.exception;

import com.ecommerce.common.constant.ErrorCode;

public class ConflictException extends BusinessException {
    public ConflictException(String message) {
        super(ErrorCode.STATE_CONFLICT, message);
    }

    public ConflictException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
