-- Flyway Migration V17: Phase 10.4-D3-E Fencing, Recovery & Versioning Schema

ALTER TABLE task_executions
    ADD COLUMN IF NOT EXISTS fencing_epoch BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS owner_token VARCHAR(64),
    ADD COLUMN IF NOT EXISTS repairs_consumed INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS recovery_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_task_executions_fencing_epoch ON task_executions(fencing_epoch);
CREATE INDEX IF NOT EXISTS idx_task_executions_status_started ON task_executions(status, started_at);
