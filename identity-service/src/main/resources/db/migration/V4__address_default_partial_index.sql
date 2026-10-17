-- 1. Deduplicate any existing multiple default addresses per user prior to index creation.
-- Keeps the most recently updated default address active, demoting older duplicates to is_default = FALSE.
WITH ranked_defaults AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY user_id
               ORDER BY updated_at DESC, created_at DESC, id DESC
           ) as rank
    FROM addresses
    WHERE is_default = TRUE AND deleted_at IS NULL
)
UPDATE addresses
SET is_default = FALSE
WHERE id IN (
    SELECT id FROM ranked_defaults WHERE rank > 1
);

-- 2. Partial unique index ensuring each user can have at most one default address among active records.
CREATE UNIQUE INDEX IF NOT EXISTS uq_addresses_user_default
    ON addresses (user_id)
    WHERE is_default = TRUE AND deleted_at IS NULL;
