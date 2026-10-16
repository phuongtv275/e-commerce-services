-- Migration: Partial unique index on addresses ensuring at most one default address per user
CREATE UNIQUE INDEX IF NOT EXISTS uq_addresses_user_default
    ON addresses (user_id)
    WHERE is_default = TRUE AND deleted_at IS NULL;
