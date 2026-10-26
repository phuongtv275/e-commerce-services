package com.example.notificationservice.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class NotificationMetrics {

    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();

    public NotificationMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void incrementQueued(String eventType) {
        getCounter("notification_queued_total", eventType).increment();
    }

    public void incrementSent(String eventType) {
        getCounter("notification_sent_total", eventType).increment();
    }

    public void incrementFailed(String eventType) {
        getCounter("notification_failed_total", eventType).increment();
    }

    public void incrementDlq(String eventType) {
        getCounter("notification_dlq_total", eventType).increment();
    }

    public void incrementRedrive(String eventType) {
        getCounter("notification_redrive_total", eventType).increment();
    }

    private Counter getCounter(String name, String eventType) {
        String safeEventType = (eventType != null && !eventType.isBlank()) ? eventType : "unknown";
        String key = name + ":" + safeEventType;
        return counters.computeIfAbsent(key, k -> Counter.builder(name)
                .tag("event_type", safeEventType)
                .tag("channel", "EMAIL")
                .description("Notification metrics for " + name)
                .register(meterRegistry));
    }
}
