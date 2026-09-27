-- Flyway Migration V20: Phase 12.2-F Pull Request Publication Schema

CREATE TABLE IF NOT EXISTS pull_request_publications (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    approval_id UUID NOT NULL REFERENCES approval_records(id) ON DELETE CASCADE,
    code_change_id UUID NOT NULL REFERENCES code_changes(id) ON DELETE CASCADE,
    repository_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    initiated_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    branch_name VARCHAR(255) NOT NULL,
    base_branch VARCHAR(255) NOT NULL,
    commit_sha VARCHAR(40) NOT NULL,
    parent_commit_sha VARCHAR(40) NOT NULL,
    tree_sha VARCHAR(40) NOT NULL,

    github_repo_id VARCHAR(255) NOT NULL,
    publication_status VARCHAR(64) NOT NULL DEFAULT 'PENDING',
    failure_category VARCHAR(64),
    failure_reason TEXT,

    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_pr_pub_approval UNIQUE (approval_id),
    CONSTRAINT uk_pr_pub_repo_branch UNIQUE (repository_id, branch_name)
);

CREATE INDEX IF NOT EXISTS idx_pr_pub_approval ON pull_request_publications(approval_id);
CREATE INDEX IF NOT EXISTS idx_pr_pub_repo ON pull_request_publications(repository_id);
CREATE INDEX IF NOT EXISTS idx_pr_pub_status ON pull_request_publications(publication_status);
