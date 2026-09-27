-- Flyway Migration V18: Phase 11 Code Review & Security Analysis Schema

CREATE TABLE IF NOT EXISTS code_reviews (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    repository_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    code_change_id UUID REFERENCES code_changes(id) ON DELETE CASCADE,
    review_mode VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'IN_PROGRESS',
    summary TEXT,
    overall_risk_score VARCHAR(20) NOT NULL DEFAULT 'LOW',
    review_context_hash VARCHAR(64) NOT NULL,
    model_identifier VARCHAR(100) NOT NULL,
    execution_time_ms BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_code_reviews_change_hash UNIQUE (code_change_id, review_context_hash)
);

CREATE TABLE IF NOT EXISTS review_findings (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    review_id UUID NOT NULL REFERENCES code_reviews(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    repository_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    code_change_id UUID REFERENCES code_changes(id) ON DELETE CASCADE,
    finding_type VARCHAR(50) NOT NULL DEFAULT 'AI_FINDING',
    category VARCHAR(100) NOT NULL,
    cwe_id VARCHAR(50),
    severity VARCHAR(20) NOT NULL,
    confidence VARCHAR(20) NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    file_path TEXT NOT NULL,
    start_line INT NOT NULL,
    end_line INT NOT NULL,
    side VARCHAR(20) NOT NULL DEFAULT 'PROPOSED',
    content_hash VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    remediation_guidance TEXT,
    status VARCHAR(50) NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_code_reviews_project_repo ON code_reviews(project_id, repository_id);
CREATE INDEX IF NOT EXISTS idx_code_reviews_task_change ON code_reviews(task_id, code_change_id);
CREATE INDEX IF NOT EXISTS idx_review_findings_review_id ON review_findings(review_id);
CREATE INDEX IF NOT EXISTS idx_review_findings_fingerprint ON review_findings(fingerprint);
CREATE INDEX IF NOT EXISTS idx_review_findings_status ON review_findings(status);
