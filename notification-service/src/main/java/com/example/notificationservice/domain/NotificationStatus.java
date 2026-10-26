package com.example.notificationservice.domain;

public enum NotificationStatus {
    QUEUED,
    SENT,
    DELIVERED,
    FAILED,
    DEAD_LETTER,
    REDRIVING,
    EXPIRED_UNSENT
}
