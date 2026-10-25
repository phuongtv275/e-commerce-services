CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- 1. CATEGORIES TABLE
CREATE TABLE IF NOT EXISTS categories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(255) NOT NULL UNIQUE,
    description TEXT,
    parent_id UUID NULL REFERENCES categories(id) ON DELETE RESTRICT,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    display_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_category_parent_not_self CHECK (parent_id IS NULL OR parent_id != id)
);

CREATE INDEX IF NOT EXISTS idx_categories_parent_id ON categories (parent_id);
CREATE INDEX IF NOT EXISTS idx_categories_status ON categories (status);

-- 2. BRANDS TABLE
CREATE TABLE IF NOT EXISTS brands (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(255) NOT NULL UNIQUE,
    description TEXT,
    logo_url VARCHAR(1000),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_brands_status ON brands (status);

-- 3. PRODUCTS TABLE
CREATE TABLE IF NOT EXISTS products (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(500) NOT NULL,
    description TEXT,
    metadata JSONB NOT NULL DEFAULT '{}',
    category_id UUID NOT NULL REFERENCES categories(id) ON DELETE RESTRICT,
    brand_id UUID NOT NULL REFERENCES brands(id) ON DELETE RESTRICT,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE', 'ARCHIVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ NULL
);

CREATE INDEX IF NOT EXISTS idx_products_category_id ON products (category_id);
CREATE INDEX IF NOT EXISTS idx_products_brand_id ON products (brand_id);
CREATE INDEX IF NOT EXISTS idx_products_status ON products (status);
CREATE INDEX IF NOT EXISTS idx_products_deleted_at ON products (deleted_at);

-- 4. SKUS TABLE
CREATE TABLE IF NOT EXISTS skus (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
    sku_code VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(500) NOT NULL,
    attributes JSONB NOT NULL DEFAULT '{}',
    price NUMERIC(19,4) NOT NULL CHECK (price >= 0 AND price <= 999999999999.9999),
    currency VARCHAR(10) NOT NULL DEFAULT 'VND' CHECK (currency = 'VND'),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ NULL
);

CREATE INDEX IF NOT EXISTS idx_skus_product_id ON skus (product_id);
CREATE INDEX IF NOT EXISTS idx_skus_status ON skus (status);
CREATE INDEX IF NOT EXISTS idx_skus_deleted_at ON skus (deleted_at);

-- 5. PRODUCT IMAGES TABLE
CREATE TABLE IF NOT EXISTS product_images (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    sku_id UUID NULL REFERENCES skus(id) ON DELETE CASCADE,
    url VARCHAR(1000) NOT NULL,
    alt_text VARCHAR(255),
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    display_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_unique_product_primary_image ON product_images (product_id) WHERE is_primary = TRUE AND sku_id IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_unique_sku_primary_image ON product_images (sku_id) WHERE is_primary = TRUE AND sku_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_product_images_product_id ON product_images (product_id);
CREATE INDEX IF NOT EXISTS idx_product_images_sku_id ON product_images (sku_id);

-- 6. CATALOG AUDIT LOGS TABLE
CREATE TABLE IF NOT EXISTS catalog_audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id VARCHAR(255),
    actor_type VARCHAR(50),
    action VARCHAR(50) NOT NULL,
    entity_type VARCHAR(50) NOT NULL,
    entity_id VARCHAR(255) NOT NULL,
    before_state JSONB,
    after_state JSONB,
    correlation_id VARCHAR(255),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_catalog_audit_logs_entity ON catalog_audit_logs (entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_catalog_audit_logs_occurred_at ON catalog_audit_logs (occurred_at);

-- 7. OUTBOX EVENTS TABLE (Matching common-library OutboxEvent)
CREATE TABLE IF NOT EXISTS outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    event_version INT NOT NULL DEFAULT 1,
    payload TEXT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    correlation_id VARCHAR(100)
);

CREATE INDEX IF NOT EXISTS idx_outbox_status_next_retry ON outbox_events (status, next_retry_at, created_at);
CREATE INDEX IF NOT EXISTS idx_outbox_aggregate ON outbox_events (aggregate_type, aggregate_id);

-- 8. PROCESSED EVENTS TABLE (Matching common-library ProcessedEvent)
CREATE TABLE IF NOT EXISTS processed_events (
    id UUID PRIMARY KEY,
    event_id VARCHAR(100) NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    expires_at TIMESTAMPTZ,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_event_consumer_group UNIQUE (event_id, consumer_group)
);

CREATE INDEX IF NOT EXISTS idx_processed_event_lookup ON processed_events (event_id, consumer_group);

-- 9. CATALOG REINDEX JOBS TABLE
CREATE TABLE IF NOT EXISTS catalog_reindex_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    status VARCHAR(50) NOT NULL DEFAULT 'QUEUED',
    target_index VARCHAR(255) NOT NULL,
    total_documents BIGINT DEFAULT 0,
    processed_documents BIGINT DEFAULT 0,
    failed_documents BIGINT DEFAULT 0,
    error_summary TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_catalog_reindex_jobs_status ON catalog_reindex_jobs (status);
