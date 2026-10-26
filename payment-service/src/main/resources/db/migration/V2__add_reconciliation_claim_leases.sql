ALTER TABLE payments ADD COLUMN reconciliation_claim_until TIMESTAMPTZ;
ALTER TABLE refunds ADD COLUMN reconciliation_claim_until TIMESTAMPTZ;

CREATE INDEX idx_payments_reconciliation_claim
    ON payments (status, created_at, reconciliation_claim_until);
CREATE INDEX idx_refunds_reconciliation_claim
    ON refunds (status, created_at, retry_count, reconciliation_claim_until);
