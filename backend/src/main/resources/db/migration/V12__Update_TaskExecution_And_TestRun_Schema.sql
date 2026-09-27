-- Flyway Migration V12: Update TaskExecution and TestRun schema for Phase 10.1 Isolated Execution Foundation

ALTER TABLE task_executions
    ADD COLUMN IF NOT EXISTS repository_id UUID REFERENCES repositories(id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS project_id UUID REFERENCES projects(id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS execution_token VARCHAR(64),
    ADD COLUMN IF NOT EXISTS ecosystem VARCHAR(50),
    ADD COLUMN IF NOT EXISTS code_change_snapshot JSONB,
    ADD COLUMN IF NOT EXISTS cancellation_requested BOOLEAN DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS cleaned_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE test_runs
    ADD COLUMN IF NOT EXISTS execution_id UUID REFERENCES task_executions(id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS ecosystem VARCHAR(50),
    ADD COLUMN IF NOT EXISTS test_target VARCHAR(255),
    ADD COLUMN IF NOT EXISTS container_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS failure_type VARCHAR(50),
    ADD COLUMN IF NOT EXISTS stdout_summary TEXT,
    ADD COLUMN IF NOT EXISTS stderr_summary TEXT,
    ADD COLUMN IF NOT EXISTS stdout_truncated BOOLEAN DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS stderr_truncated BOOLEAN DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_task_executions_task_id ON task_executions(task_id);
CREATE INDEX IF NOT EXISTS idx_task_executions_repo_id ON task_executions(repository_id);
CREATE INDEX IF NOT EXISTS idx_test_runs_execution_id ON test_runs(execution_id);
