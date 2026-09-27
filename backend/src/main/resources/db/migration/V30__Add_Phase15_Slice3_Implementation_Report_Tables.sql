-- Flyway Migration V30: Phase 15.3 Implementation Report Schema & Immutability Trigger

CREATE TABLE IF NOT EXISTS implementation_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    repository_id UUID REFERENCES repositories(id) ON DELETE RESTRICT,
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE RESTRICT,
    task_execution_id UUID NOT NULL REFERENCES task_executions(id) ON DELETE RESTRICT,
    status VARCHAR(50) NOT NULL DEFAULT 'GENERATED',
    execution_outcome VARCHAR(50) NOT NULL,
    changed_files_summary JSONB,
    test_summary JSONB,
    security_review_summary JSONB,
    confidence_score NUMERIC(5,2),
    source_context_hash VARCHAR(64) NOT NULL,
    correlation_id VARCHAR(64) NOT NULL,
    failure_reason TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_impl_reports_execution_id UNIQUE (task_execution_id)
);

CREATE INDEX IF NOT EXISTS idx_impl_reports_task_id ON implementation_reports(task_id);
CREATE INDEX IF NOT EXISTS idx_impl_reports_project_id ON implementation_reports(project_id);

-- Immutability Trigger: Reject UPDATE and DELETE
CREATE OR REPLACE FUNCTION prevent_implementation_report_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'ImplementationReport is immutable and cannot be updated or deleted.';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prevent_implementation_reports_update_delete ON implementation_reports;

CREATE TRIGGER trg_prevent_implementation_reports_update_delete
BEFORE UPDATE OR DELETE ON implementation_reports
FOR EACH ROW EXECUTE FUNCTION prevent_implementation_report_modification();
