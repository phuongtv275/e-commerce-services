CREATE TABLE IF NOT EXISTS sku_stock_snapshots (
    sku_id UUID PRIMARY KEY,
    in_stock BOOLEAN NOT NULL,
    inventory_version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_sku_stock_snapshots_updated_at ON sku_stock_snapshots (updated_at);
