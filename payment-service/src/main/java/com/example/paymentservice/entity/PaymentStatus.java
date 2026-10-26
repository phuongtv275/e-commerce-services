package com.example.paymentservice.entity;

public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    EXPIRED,
    REFUND_PENDING,
    PARTIALLY_REFUNDED,
    REFUNDED,
    MANUAL_RECONCILIATION_REQUIRED
}
