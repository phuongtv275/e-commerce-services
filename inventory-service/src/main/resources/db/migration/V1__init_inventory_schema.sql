CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE warehouses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    code VARCHAR(100) NOT NULL UNIQUE,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_warehouses_active_default ON warehouses (is_default) WHERE is_default AND status = 'ACTIVE';
INSERT INTO warehouses (name, code, is_default, status) VALUES ('Default Warehouse', 'WH_DEFAULT', TRUE, 'ACTIVE')
ON CONFLICT (code) DO NOTHING;

CREATE TABLE stocks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sku_id UUID NOT NULL,
    warehouse_id UUID NOT NULL REFERENCES warehouses(id) ON DELETE RESTRICT,
    on_hand INTEGER NOT NULL CHECK (on_hand >= 0),
    reserved INTEGER NOT NULL CHECK (reserved >= 0),
    available INTEGER GENERATED ALWAYS AS (on_hand - reserved) STORED,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_stocks_sku_warehouse UNIQUE (sku_id, warehouse_id),
    CONSTRAINT chk_stocks_on_hand_reserved CHECK (on_hand >= reserved)
);

CREATE TABLE reservations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL UNIQUE,
    warehouse_id UUID NOT NULL REFERENCES warehouses(id) ON DELETE RESTRICT,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'COMMITTED', 'RELEASED', 'EXPIRED', 'RESTOCKED')),
    expires_at TIMESTAMPTZ NOT NULL,
    committed_at TIMESTAMPTZ,
    released_at TIMESTAMPTZ,
    correlation_id VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_reservations_expiry ON reservations (status, expires_at, id);

CREATE TABLE reservation_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reservation_id UUID NOT NULL REFERENCES reservations(id) ON DELETE CASCADE,
    sku_id UUID NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    CONSTRAINT uq_reservation_item_sku UNIQUE (reservation_id, sku_id)
);

CREATE TABLE stock_movements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sku_id UUID NOT NULL,
    warehouse_id UUID NOT NULL REFERENCES warehouses(id) ON DELETE RESTRICT,
    movement_type VARCHAR(20) NOT NULL CHECK (movement_type IN ('INBOUND', 'ADJUSTMENT', 'RESERVE', 'RELEASE', 'OUTBOUND', 'RESTOCK')),
    quantity_delta INTEGER NOT NULL,
    reference_type VARCHAR(50) NOT NULL,
    reference_id VARCHAR(255) NOT NULL,
    before_on_hand INTEGER NOT NULL CHECK (before_on_hand >= 0),
    after_on_hand INTEGER NOT NULL CHECK (after_on_hand >= 0),
    before_reserved INTEGER NOT NULL CHECK (before_reserved >= 0),
    after_reserved INTEGER NOT NULL CHECK (after_reserved >= 0),
    actor VARCHAR(255),
    reason VARCHAR(500),
    correlation_id VARCHAR(255),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_stock_movements_sku_time ON stock_movements (sku_id, occurred_at, id);
CREATE UNIQUE INDEX uq_stock_movement_reference_sku ON stock_movements (reference_type, reference_id, sku_id) WHERE movement_type IN ('INBOUND', 'ADJUSTMENT');
CREATE FUNCTION reject_stock_movement_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'stock_movements is append-only';
END;
$$;
CREATE TRIGGER trg_stock_movements_append_only BEFORE UPDATE OR DELETE ON stock_movements
FOR EACH ROW EXECUTE FUNCTION reject_stock_movement_mutation();

CREATE TABLE inventory_quarantine_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL,
    payload JSONB NOT NULL,
    error_reason VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'MANUAL_RECONCILIATION_REQUIRED',
    resolved_at TIMESTAMPTZ,
    resolved_by VARCHAR(255),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_inventory_quarantine_status_time ON inventory_quarantine_logs (status, occurred_at);

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
