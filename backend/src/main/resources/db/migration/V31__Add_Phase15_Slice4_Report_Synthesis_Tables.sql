-- Flyway Migration V31: Phase 15.4 Report Synthesis Schema & Immutability Trigger

CREATE TABLE IF NOT EXISTS report_syntheses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    implementation_report_id UUID NOT NULL REFERENCES implementation_reports(id) ON DELETE CASCADE,
    task_execution_id UUID NOT NULL REFERENCES task_executions(id) ON DELETE RESTRICT,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    source_context_hash VARCHAR(64) NOT NULL,
    ai_synthesis_input_hash VARCHAR(64) NOT NULL,
    synthesizer_version VARCHAR(50) NOT NULL DEFAULT 'v1.0',
    prompt_version VARCHAR(50) NOT NULL DEFAULT 'v1.0',
    model_identifier VARCHAR(100) NOT NULL DEFAULT 'repopilot-report-synthesizer-v1',
    validator_version VARCHAR(50) NOT NULL DEFAULT 'v1.0',
    executive_summary TEXT NOT NULL,
    implementation_explanation TEXT NOT NULL,
    testing_explanation TEXT NOT NULL,
    security_explanation TEXT NOT NULL,
    limitations_json JSONB,
    developer_notes_json JSONB,
    rendered_markdown TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_report_syntheses_input_hash UNIQUE (implementation_report_id, ai_synthesis_input_hash)
);

CREATE INDEX IF NOT EXISTS idx_report_syntheses_report_id ON report_syntheses(implementation_report_id);
CREATE INDEX IF NOT EXISTS idx_report_syntheses_execution_id ON report_syntheses(task_execution_id);

-- Immutability Trigger: Reject UPDATE and DELETE
CREATE OR REPLACE FUNCTION prevent_report_synthesis_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'ReportSynthesis is immutable and cannot be updated or deleted.';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prevent_report_syntheses_update_delete ON report_syntheses;

CREATE TRIGGER trg_prevent_report_syntheses_update_delete
BEFORE UPDATE OR DELETE ON report_syntheses
FOR EACH ROW EXECUTE FUNCTION prevent_report_synthesis_modification();
