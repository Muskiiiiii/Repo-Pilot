-- Flyway Migration V22: Phase 12.2-F-G Publication Audit Events Schema

CREATE TABLE IF NOT EXISTS publication_audit_events (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    event_sequence BIGINT AUTO_INCREMENT NOT NULL,
    event_timestamp TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    repository_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    code_change_id UUID NOT NULL REFERENCES code_changes(id) ON DELETE CASCADE,
    approval_id UUID REFERENCES approval_records(id) ON DELETE SET NULL,
    publication_id UUID REFERENCES pull_request_publications(id) ON DELETE SET NULL,

    actor_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    actor_email VARCHAR(255),
    actor_role VARCHAR(64),

    event_type VARCHAR(64) NOT NULL,
    previous_state VARCHAR(64),
    resulting_state VARCHAR(64),

    github_repo_id VARCHAR(255),
    repo_owner VARCHAR(255),
    repo_name VARCHAR(255),
    base_branch VARCHAR(255),
    branch_name VARCHAR(255),
    base_sha VARCHAR(40),
    commit_sha VARCHAR(40),
    parent_commit_sha VARCHAR(40),
    tree_sha VARCHAR(40),
    diff_hash VARCHAR(64),

    github_pr_number BIGINT,
    pr_url TEXT,
    outcome_category VARCHAR(64),
    reason_code VARCHAR(128),
    summary TEXT,

    metadata_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_pub_audit_seq UNIQUE (event_sequence)
);

CREATE INDEX IF NOT EXISTS idx_audit_proj_repo_task ON publication_audit_events(project_id, repository_id, task_id);
CREATE INDEX IF NOT EXISTS idx_audit_code_change ON publication_audit_events(code_change_id);
CREATE INDEX IF NOT EXISTS idx_audit_approval ON publication_audit_events(approval_id);
CREATE INDEX IF NOT EXISTS idx_audit_event_type ON publication_audit_events(event_type);
CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON publication_audit_events(event_timestamp);
CREATE INDEX IF NOT EXISTS idx_audit_seq ON publication_audit_events(event_sequence);
