-- Flyway Migration V13: Enforce Persistent DB-Backed Execution Concurrency Constraint
-- Guarantees that at most one active execution (PREPARING or RUNNING) can exist per task in PostgreSQL

CREATE UNIQUE INDEX IF NOT EXISTS uk_task_executions_active_task
ON task_executions (task_id)
WHERE status IN ('PREPARING', 'RUNNING');
