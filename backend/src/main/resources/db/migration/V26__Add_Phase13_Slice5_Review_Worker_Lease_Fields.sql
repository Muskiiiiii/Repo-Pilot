-- Flyway Migration V26: Add Phase 13 Slice 5 Review Worker Lease & Fencing Fields

ALTER TABLE code_reviews
    ADD COLUMN IF NOT EXISTS worker_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS lease_token VARCHAR(64),
    ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS execution_attempt_count INT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_code_reviews_claim_recovery ON code_reviews(status, lease_expires_at);
