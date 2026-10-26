CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL UNIQUE,
    customer_id UUID NOT NULL,
    amount NUMERIC(19, 0) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'VND',
    provider VARCHAR(50) NOT NULL DEFAULT 'VNPAY',
    status VARCHAR(50) NOT NULL CHECK (status IN (
        'PENDING', 'SUCCEEDED', 'FAILED', 'EXPIRED',
        'REFUND_PENDING', 'PARTIALLY_REFUNDED', 'REFUNDED',
        'MANUAL_RECONCILIATION_REQUIRED'
    )),
    provider_txn_ref VARCHAR(100) NOT NULL UNIQUE,
    provider_create_date CHAR(14) NOT NULL,
    provider_transaction_no VARCHAR(15),
    payment_url TEXT,
    total_refunded NUMERIC(19, 0) NOT NULL DEFAULT 0 CHECK (total_refunded >= 0),
    refund_in_progress NUMERIC(19, 0) NOT NULL DEFAULT 0 CHECK (refund_in_progress >= 0),
    paid_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_payments_refund_ceiling CHECK (total_refunded + refund_in_progress <= amount)
);

CREATE INDEX idx_payments_status_created ON payments (status, created_at);
CREATE INDEX idx_payments_customer_id ON payments (customer_id);

CREATE TABLE refunds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id UUID NOT NULL REFERENCES payments(id) ON DELETE RESTRICT,
    order_id UUID NOT NULL,
    amount NUMERIC(19, 0) NOT NULL CHECK (amount > 0),
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(50) NOT NULL CHECK (status IN (
        'PENDING', 'SUCCEEDED', 'FAILED', 'MANUAL_RECONCILIATION_REQUIRED'
    )),
    requested_by UUID,
    provider_request_id VARCHAR(32) NOT NULL UNIQUE,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    provider_transaction_no VARCHAR(15),
    retry_count INT NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
    processed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_refunds_status_created_id ON refunds (status, created_at, id);
CREATE INDEX idx_refunds_payment_id ON refunds (payment_id);
CREATE INDEX idx_refunds_order_id ON refunds (order_id);

CREATE TABLE payment_audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id UUID NOT NULL,
    refund_id UUID,
    action VARCHAR(100) NOT NULL,
    old_status VARCHAR(50),
    new_status VARCHAR(50) NOT NULL,
    actor VARCHAR(255) NOT NULL,
    correlation_id VARCHAR(255),
    raw_payload JSONB,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_audit_payment_time ON payment_audit_logs (payment_id, occurred_at);

CREATE OR REPLACE FUNCTION reject_payment_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'payment_audit_logs is append-only';
END;
$$;

CREATE TRIGGER trg_payment_audit_logs_append_only BEFORE UPDATE OR DELETE ON payment_audit_logs
FOR EACH ROW EXECUTE FUNCTION reject_payment_audit_mutation();

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    event_version INTEGER NOT NULL DEFAULT 1,
    payload TEXT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    retry_count INTEGER NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    correlation_id VARCHAR(100)
);

CREATE INDEX idx_outbox_status_next_retry ON outbox_events (status, next_retry_at, created_at);
CREATE INDEX idx_outbox_aggregate ON outbox_events (aggregate_type, aggregate_id);

CREATE TABLE processed_events (
    id UUID PRIMARY KEY,
    event_id VARCHAR(100) NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    expires_at TIMESTAMPTZ,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_event_consumer_group UNIQUE (event_id, consumer_group)
);

CREATE INDEX idx_processed_event_lookup ON processed_events (event_id, consumer_group);
