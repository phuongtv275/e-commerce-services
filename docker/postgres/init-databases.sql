-- Initialize 6 independent databases for microservices
CREATE DATABASE identity_db;
CREATE DATABASE catalog_db;
CREATE DATABASE inventory_db;
CREATE DATABASE order_db;
CREATE DATABASE payment_db;
CREATE DATABASE notification_db;

\c identity_db;
CREATE UNIQUE INDEX IF NOT EXISTS uq_addresses_user_default
    ON addresses (user_id)
    WHERE is_default = TRUE AND deleted_at IS NULL;
