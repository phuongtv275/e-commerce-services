package com.ecommerce.common.outbox;

public enum OutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED_RETRYABLE,
    DEAD_LETTER
}
