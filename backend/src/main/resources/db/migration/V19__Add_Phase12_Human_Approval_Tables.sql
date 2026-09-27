-- Flyway Migration V19: Phase 12 Human Approval Schema

CREATE TABLE IF NOT EXISTS approval_records (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    repository_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    code_change_id UUID NOT NULL REFERENCES code_changes(id) ON DELETE CASCADE,
    review_id UUID NOT NULL REFERENCES code_reviews(id) ON DELETE CASCADE,
    approval_version INT NOT NULL DEFAULT 1,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING_APPROVAL',
    repository_base_sha VARCHAR(40) NOT NULL,
    code_change_original_hash VARCHAR(64) NOT NULL,
    code_change_proposed_hash VARCHAR(64) NOT NULL,
    code_change_diff_hash VARCHAR(64) NOT NULL,
    review_context_hash VARCHAR(64) NOT NULL,
    approved_files TEXT NOT NULL,
    approved_change_count INT NOT NULL DEFAULT 1,
    approval_record_hash VARCHAR(64) NOT NULL,
    approved_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    approved_by_user_email VARCHAR(255) NOT NULL,
    approved_by_user_role VARCHAR(50) NOT NULL,
    approved_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    stale_reason VARCHAR(255),
    idempotency_key VARCHAR(128) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_approval_change_version UNIQUE (code_change_id, approval_version),
    CONSTRAINT uk_approval_idempotency_key UNIQUE (idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_approval_records_project ON approval_records(project_id);
CREATE INDEX IF NOT EXISTS idx_approval_records_task ON approval_records(task_id);
CREATE INDEX IF NOT EXISTS idx_approval_records_change ON approval_records(code_change_id);
CREATE INDEX IF NOT EXISTS idx_approval_records_review ON approval_records(review_id);
CREATE INDEX IF NOT EXISTS idx_approval_records_user ON approval_records(approved_by_user_id);
CREATE INDEX IF NOT EXISTS idx_approval_records_hash ON approval_records(approval_record_hash);
