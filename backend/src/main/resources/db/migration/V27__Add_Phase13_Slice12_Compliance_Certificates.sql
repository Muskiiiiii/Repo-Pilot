-- Flyway Migration V27: Phase 13 Slice 12 Compliance Certificates Schema

CREATE TABLE IF NOT EXISTS compliance_certificates (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    repository_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    code_review_id UUID NOT NULL REFERENCES code_reviews(id) ON DELETE CASCADE,
    code_change_id UUID NOT NULL REFERENCES code_changes(id) ON DELETE CASCADE,
    compliance_status VARCHAR(30) NOT NULL,
    policy_version VARCHAR(50) NOT NULL DEFAULT 'v1.0.0',
    review_context_hash VARCHAR(64) NOT NULL,
    finding_evidence_hash VARCHAR(64) NOT NULL,
    critical_unmitigated_count INT NOT NULL DEFAULT 0,
    high_unmitigated_count INT NOT NULL DEFAULT 0,
    medium_unmitigated_count INT NOT NULL DEFAULT 0,
    low_unmitigated_count INT NOT NULL DEFAULT 0,
    mitigated_findings_count INT NOT NULL DEFAULT 0,
    admin_override_reason TEXT,
    admin_override_by_user_id UUID REFERENCES users(id) ON DELETE RESTRICT,
    issued_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    idempotency_key VARCHAR(128) NOT NULL,
    certificate_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_compliance_cert_project_review_idempotency UNIQUE (project_id, code_review_id, idempotency_key),
    CONSTRAINT uk_compliance_cert_idempotency_global UNIQUE (idempotency_key),
    CONSTRAINT uk_compliance_cert_hash UNIQUE (certificate_hash)
);

CREATE INDEX IF NOT EXISTS idx_compliance_certs_review ON compliance_certificates(code_review_id);
CREATE INDEX IF NOT EXISTS idx_compliance_certs_project_repo ON compliance_certificates(project_id, repository_id);
