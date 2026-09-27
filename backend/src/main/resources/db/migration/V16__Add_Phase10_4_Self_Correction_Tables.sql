-- Flyway Migration V16: Phase 10.4 Self-Correction & Repair Lineage Schema

CREATE TABLE IF NOT EXISTS repair_iterations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    execution_id UUID NOT NULL REFERENCES task_executions(id) ON DELETE CASCADE,
    iteration_number INT NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'CREATED',
    failure_category VARCHAR(50),
    repairability VARCHAR(50),
    parent_code_change_id UUID REFERENCES code_changes(id),
    applied_code_change_id UUID REFERENCES code_changes(id),
    rejection_reason TEXT,
    version INT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_repair_iterations_exec_num UNIQUE (execution_id, iteration_number)
);

CREATE TABLE IF NOT EXISTS ai_diagnoses (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    repair_iteration_id UUID NOT NULL REFERENCES repair_iterations(id) ON DELETE CASCADE,
    root_cause_description TEXT NOT NULL,
    failure_mechanism VARCHAR(50) NOT NULL,
    proposed_fix_strategy TEXT,
    confidence_score VARCHAR(20) NOT NULL,
    raw_response JSONB,
    model_identifier VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS repair_proposals (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    repair_iteration_id UUID NOT NULL REFERENCES repair_iterations(id) ON DELETE CASCADE,
    diagnosis_id UUID REFERENCES ai_diagnoses(id) ON DELETE CASCADE,
    file_modifications JSONB NOT NULL,
    validation_status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    validation_reason TEXT,
    parent_snapshot_hash VARCHAR(64) NOT NULL,
    resulting_tree_hash VARCHAR(64),
    diff_hash VARCHAR(64) NOT NULL,
    raw_response JSONB,
    model_identifier VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_repair_iterations_execution_id ON repair_iterations(execution_id);
CREATE INDEX IF NOT EXISTS idx_ai_diagnoses_iteration_id ON ai_diagnoses(repair_iteration_id);
CREATE INDEX IF NOT EXISTS idx_repair_proposals_iteration_id ON repair_proposals(repair_iteration_id);
