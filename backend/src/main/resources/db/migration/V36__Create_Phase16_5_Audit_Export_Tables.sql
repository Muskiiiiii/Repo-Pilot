-- V36__Create_Phase16_5_Audit_Export_Tables.sql

-- 1. Main Audit Export Jobs Table
CREATE TABLE audit_export_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    requested_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    status VARCHAR(32) NOT NULL,
    snapshot_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    max_sequence_number BIGINT NOT NULL DEFAULT 0,
    max_audit_outbox_id UUID NULL,
    export_scope VARCHAR(64) NOT NULL,
    filter_from_timestamp TIMESTAMP WITH TIME ZONE NULL,
    filter_to_timestamp TIMESTAMP WITH TIME ZONE NULL,
    lease_token UUID NULL,
    lease_expires_at TIMESTAMP WITH TIME ZONE NULL,
    worker_identity VARCHAR(128) NULL,
    fencing_version BIGINT NOT NULL DEFAULT 1,
    attempt_count INT NOT NULL DEFAULT 0,
    key_version INT NOT NULL DEFAULT 1,
    encrypted_dek BYTEA NULL,
    gcm_nonce BYTEA NULL,
    artifact_storage_path VARCHAR(512) NULL,
    artifact_checksum_sha256 VARCHAR(64) NULL,
    encrypted_artifact_checksum_sha256 VARCHAR(64) NULL,
    artifact_size_bytes BIGINT NULL,
    record_count BIGINT NULL,
    legal_hold BOOLEAN NOT NULL DEFAULT FALSE,
    artifact_purged BOOLEAN NOT NULL DEFAULT FALSE,
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(512) NULL,
    idempotency_key VARCHAR(128) NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP WITH TIME ZONE NULL,
    completed_at TIMESTAMP WITH TIME ZONE NULL,
    expires_at TIMESTAMP WITH TIME ZONE NULL,

    CONSTRAINT chk_audit_export_status CHECK (status IN ('PENDING', 'CLAIMED', 'PROCESSING', 'COMPLETED', 'FAILED', 'EXPIRED', 'CANCELLED', 'DELETED')),
    CONSTRAINT uk_audit_export_job_org_idempotency UNIQUE (organization_id, idempotency_key)
);

CREATE INDEX idx_audit_export_jobs_org_created ON audit_export_jobs(organization_id, created_at DESC);
CREATE INDEX idx_audit_export_jobs_status_lease ON audit_export_jobs(status, lease_expires_at);

-- 2. Durable Consumed Download Tokens Table (Single-Use Token Enforcement)
CREATE TABLE consumed_download_tokens (
    jti_hash VARCHAR(64) PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES audit_export_jobs(id) ON DELETE CASCADE,
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    consumed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_consumed_download_tokens_expires ON consumed_download_tokens(expires_at);

-- 3. Monotonic Sequence Number for Audit Log Outbox
CREATE SEQUENCE IF NOT EXISTS audit_log_outbox_seq START WITH 1 INCREMENT BY 1;
ALTER TABLE audit_log_outbox ADD COLUMN IF NOT EXISTS sequence_number BIGINT NOT NULL DEFAULT nextval('audit_log_outbox_seq');
CREATE UNIQUE INDEX IF NOT EXISTS uk_audit_log_outbox_seq ON audit_log_outbox(sequence_number);
