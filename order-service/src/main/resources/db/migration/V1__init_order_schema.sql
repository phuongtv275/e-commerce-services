CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE carts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id UUID NOT NULL UNIQUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE cart_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cart_id UUID NOT NULL REFERENCES carts(id) ON DELETE CASCADE,
    sku_id UUID NOT NULL,
    sku_code VARCHAR(100),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(19,4) NOT NULL CHECK (unit_price >= 0),
    added_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_cart_sku UNIQUE (cart_id, sku_id)
);
CREATE INDEX idx_cart_items_cart_id ON cart_items (cart_id);

CREATE TABLE orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_code VARCHAR(50) NOT NULL UNIQUE,
    customer_id UUID NOT NULL,
    status VARCHAR(30) NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'SHIPPING', 'COMPLETED', 'CANCELLED')),
    subtotal NUMERIC(19,4) NOT NULL CHECK (subtotal >= 0),
    discount NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (discount >= 0),
    tax NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (tax >= 0),
    shipping_fee NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (shipping_fee >= 0),
    total_amount NUMERIC(19,4) NOT NULL CHECK (total_amount >= 0),
    currency CHAR(3) NOT NULL DEFAULT 'VND',
    idempotency_key VARCHAR(255),
    shipping_address JSONB NOT NULL,
    contact_info JSONB NOT NULL,
    payment_method VARCHAR(50) NOT NULL DEFAULT 'VNPAY',
    cancel_reason VARCHAR(500),
    cancelled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_order_total CHECK (subtotal - discount + tax + shipping_fee = total_amount)
);
CREATE INDEX idx_orders_customer_created ON orders (customer_id, created_at DESC, id DESC);

CREATE TABLE order_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
    sku_id UUID NOT NULL,
    sku_code VARCHAR(100) NOT NULL,
    product_name VARCHAR(255) NOT NULL,
    sku_name VARCHAR(255),
    attributes JSONB NOT NULL,
    unit_price NUMERIC(19,4) NOT NULL CHECK (unit_price >= 0),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    line_total NUMERIC(19,4) NOT NULL CHECK (line_total >= 0)
);
CREATE INDEX idx_order_items_order_id ON order_items (order_id);

CREATE TABLE order_status_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
    old_status VARCHAR(30),
    new_status VARCHAR(30) NOT NULL,
    reason VARCHAR(500),
    actor VARCHAR(255) NOT NULL,
    correlation_id VARCHAR(100),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_order_status_history_order ON order_status_history (order_id, occurred_at);

CREATE TABLE idempotency_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id UUID NOT NULL,
    operation VARCHAR(50) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED')),
    attempt_order_id UUID,
    response_status INTEGER,
    response_body JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_idempotency_customer_op_key UNIQUE (customer_id, operation, idempotency_key)
);
CREATE INDEX idx_idempotency_expiry ON idempotency_records (status, expires_at);

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
    CONSTRAINT uq_order_event_consumer_group UNIQUE (event_id, consumer_group)
);
CREATE INDEX idx_order_processed_event_lookup ON processed_events (event_id, consumer_group);
