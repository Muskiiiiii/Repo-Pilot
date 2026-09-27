-- V15: Add Phase 10.3 Failure Classification and Repair Eligibility Fields to test_runs

ALTER TABLE test_runs
    ADD COLUMN IF NOT EXISTS failure_category VARCHAR(50) DEFAULT 'UNKNOWN',
    ADD COLUMN IF NOT EXISTS repairability VARCHAR(50) DEFAULT 'AMBIGUOUS',
    ADD COLUMN IF NOT EXISTS confidence VARCHAR(50) DEFAULT 'LOW',
    ADD COLUMN IF NOT EXISTS classification_reason TEXT,
    ADD COLUMN IF NOT EXISTS classification_evidence JSONB,
    ADD COLUMN IF NOT EXISTS classified_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS classifier_version VARCHAR(50) DEFAULT '10.3-v1';
