-- Flyway Migration V28: Phase 14 Slice 1 Sandbox Execution Schema

CREATE TABLE IF NOT EXISTS sandbox_worker_nodes (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    node_identifier VARCHAR(100) NOT NULL UNIQUE,
    socket_path VARCHAR(255) NOT NULL,
    max_concurrent_containers INT NOT NULL DEFAULT 4 CHECK (max_concurrent_containers > 0),
    active_containers INT NOT NULL DEFAULT 0 CHECK (active_containers >= 0),
    status VARCHAR(30) NOT NULL DEFAULT 'HEALTHY',
    last_heartbeat_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sandbox_job_executions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    task_execution_id UUID NOT NULL REFERENCES task_executions(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    repository_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    assigned_worker_node_id UUID REFERENCES sandbox_worker_nodes(id) ON DELETE SET NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    lease_token UUID NOT NULL DEFAULT uuid_generate_v4(),
    fencing_version BIGINT NOT NULL DEFAULT 1 CHECK (fencing_version >= 1),
    attempt_count INT NOT NULL DEFAULT 1 CHECK (attempt_count >= 1),
    command_type VARCHAR(50) NOT NULL,
    command_args TEXT NOT NULL,
    container_id VARCHAR(128),
    exit_code INT,
    cpu_limit_cores NUMERIC(3,1) NOT NULL DEFAULT 2.0 CHECK (cpu_limit_cores > 0),
    memory_limit_mb INT NOT NULL DEFAULT 2048 CHECK (memory_limit_mb > 0),
    timeout_seconds INT NOT NULL DEFAULT 120 CHECK (timeout_seconds > 0),
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sandbox_job_task_exec UNIQUE (task_execution_id, command_type)
);

CREATE TABLE IF NOT EXISTS sandbox_execution_artifacts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    job_execution_id UUID NOT NULL REFERENCES sandbox_job_executions(id) ON DELETE CASCADE,
    artifact_type VARCHAR(50) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    raw_output TEXT,
    sanitized_output TEXT NOT NULL,
    secret_scrubbed_count INT NOT NULL DEFAULT 0 CHECK (secret_scrubbed_count >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_sandbox_jobs_status_expires ON sandbox_job_executions(status, lease_expires_at);
CREATE INDEX IF NOT EXISTS idx_sandbox_jobs_project_repo ON sandbox_job_executions(project_id, repository_id);
CREATE INDEX IF NOT EXISTS idx_sandbox_jobs_task_exec ON sandbox_job_executions(task_execution_id);
CREATE INDEX IF NOT EXISTS idx_sandbox_artifacts_job ON sandbox_execution_artifacts(job_execution_id);
