-- V3: Align outbox_events and processed_events with common-library JPA entities

ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS event_version INT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_error TEXT,
    ADD COLUMN IF NOT EXISTS published_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(100);

CREATE INDEX IF NOT EXISTS idx_outbox_status_next_retry ON outbox_events(status, next_retry_at, created_at);
CREATE INDEX IF NOT EXISTS idx_outbox_aggregate ON outbox_events(aggregate_type, aggregate_id);

CREATE TABLE IF NOT EXISTS processed_events (
    id UUID PRIMARY KEY,
    event_id VARCHAR(100) NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    expires_at TIMESTAMPTZ,
    processed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_event_consumer_group UNIQUE (event_id, consumer_group)
);

CREATE INDEX IF NOT EXISTS idx_processed_event_lookup ON processed_events(event_id, consumer_group);
