CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 1. Notification Templates
CREATE TABLE notification_templates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type VARCHAR(100) NOT NULL,
    channel VARCHAR(50) NOT NULL DEFAULT 'EMAIL',
    language VARCHAR(10) NOT NULL DEFAULT 'vi',
    subject_template TEXT NOT NULL,
    body_template TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_notification_template_version UNIQUE (event_type, channel, language, version)
);

CREATE UNIQUE INDEX uq_active_notification_template
    ON notification_templates (event_type, channel, language)
    WHERE is_active = TRUE;

-- 2. Notification Logs
CREATE TABLE notification_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    channel VARCHAR(50) NOT NULL DEFAULT 'EMAIL',
    recipient VARCHAR(500) NOT NULL,
    subject TEXT NOT NULL,
    rendered_body TEXT NOT NULL,
    body_preview TEXT,
    payload JSONB,
    status VARCHAR(50) NOT NULL DEFAULT 'QUEUED',
    provider_message_id VARCHAR(255),
    retry_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    error_message TEXT,
    correlation_id VARCHAR(255),
    queued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    claim_token UUID,
    CONSTRAINT uq_notification_event_channel UNIQUE (event_id, channel),
    CONSTRAINT chk_notification_status CHECK (
        status IN ('QUEUED', 'SENT', 'DELIVERED', 'FAILED', 'DEAD_LETTER', 'REDRIVING', 'EXPIRED_UNSENT')
    )
);

CREATE INDEX idx_notification_logs_status_next_attempt ON notification_logs (status, next_attempt_at);
CREATE INDEX idx_notification_logs_queued_at ON notification_logs (queued_at);
CREATE INDEX idx_notification_logs_status_updated ON notification_logs (status, updated_at);

-- 3. Processed Events (align with common-library)
CREATE TABLE processed_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id VARCHAR(100) NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    expires_at TIMESTAMPTZ,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_notification_processed_event UNIQUE (event_id, consumer_group)
);

CREATE INDEX idx_notification_processed_event_lookup ON processed_events (event_id, consumer_group);
