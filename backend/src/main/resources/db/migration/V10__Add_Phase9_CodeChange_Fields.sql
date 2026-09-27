-- Migration: Add Phase 9 CodeChange fields
ALTER TABLE code_changes
    ADD COLUMN IF NOT EXISTS repository_id UUID REFERENCES repositories(id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS original_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS proposed_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS affected_symbols JSONB,
    ADD COLUMN IF NOT EXISTS evidence_references JSONB,
    ADD COLUMN IF NOT EXISTS confidence_level VARCHAR(20) DEFAULT 'MEDIUM',
    ADD COLUMN IF NOT EXISTS start_line INTEGER,
    ADD COLUMN IF NOT EXISTS end_line INTEGER,
    ADD COLUMN IF NOT EXISTS validation_failure_reason TEXT,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_code_changes_task_id ON code_changes(task_id);
CREATE INDEX IF NOT EXISTS idx_code_changes_plan_id ON code_changes(plan_id);
CREATE INDEX IF NOT EXISTS idx_code_changes_repository_id ON code_changes(repository_id);
