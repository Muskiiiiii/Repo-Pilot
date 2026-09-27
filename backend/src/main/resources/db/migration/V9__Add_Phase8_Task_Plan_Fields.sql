-- Flyway Migration V9: Add Phase 8 Task and Plan fields for issue understanding and evidence-linked planning

ALTER TABLE tasks ADD COLUMN IF NOT EXISTS issue_understanding JSONB;

ALTER TABLE plans ADD COLUMN IF NOT EXISTS problem_analysis TEXT;
ALTER TABLE plans ADD COLUMN IF NOT EXISTS relevant_symbols JSONB;
ALTER TABLE plans ADD COLUMN IF NOT EXISTS current_behavior TEXT;
ALTER TABLE plans ADD COLUMN IF NOT EXISTS proposed_changes JSONB;
ALTER TABLE plans ADD COLUMN IF NOT EXISTS data_api_changes TEXT;
ALTER TABLE plans ADD COLUMN IF NOT EXISTS testing_strategy JSONB;
ALTER TABLE plans ADD COLUMN IF NOT EXISTS assumptions JSONB;
ALTER TABLE plans ADD COLUMN IF NOT EXISTS open_questions JSONB;
ALTER TABLE plans ADD COLUMN IF NOT EXISTS evidence_references JSONB;
ALTER TABLE plans ADD COLUMN IF NOT EXISTS confidence_level VARCHAR(20) DEFAULT 'MEDIUM';
ALTER TABLE plans ADD COLUMN IF NOT EXISTS is_ambiguous BOOLEAN DEFAULT FALSE;
ALTER TABLE plans ADD COLUMN IF NOT EXISTS ambiguity_reason TEXT;
