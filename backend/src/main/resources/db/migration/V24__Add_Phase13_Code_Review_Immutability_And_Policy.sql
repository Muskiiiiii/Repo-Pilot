-- Flyway Migration V24: Phase 13 Code Review Immutability, Policy & Retention

ALTER TABLE code_reviews
    ADD COLUMN IF NOT EXISTS ai_input_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS policy_version VARCHAR(50) NOT NULL DEFAULT 'v1.0.0',
    ADD COLUMN IF NOT EXISTS reviewer_version VARCHAR(50) NOT NULL DEFAULT '1.0.0',
    ADD COLUMN IF NOT EXISTS prompt_version VARCHAR(50) NOT NULL DEFAULT 'p2026.1',
    ADD COLUMN IF NOT EXISTS validator_version VARCHAR(50) NOT NULL DEFAULT '1.0.0',
    ADD COLUMN IF NOT EXISTS admin_override_reason TEXT,
    ADD COLUMN IF NOT EXISTS admin_override_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS admin_override_at TIMESTAMP WITH TIME ZONE;

-- Database Level Immutability Trigger for code_reviews (PostgreSQL 16)
CREATE OR REPLACE FUNCTION prevent_code_review_unauthorized_update()
RETURNS TRIGGER AS $$
BEGIN
    -- Section 1: Check admin_override_* field modifications (Applies to ALL review statuses)
    IF (NEW.admin_override_reason IS DISTINCT FROM OLD.admin_override_reason OR
        NEW.admin_override_by_user_id IS DISTINCT FROM OLD.admin_override_by_user_id OR
        NEW.admin_override_at IS DISTINCT FROM OLD.admin_override_at) THEN

        -- Rule 1.1: Override is permitted ONLY when OLD.status = 'COMPLETED'
        IF OLD.status <> 'COMPLETED' THEN
            RAISE EXCEPTION 'Admin override is permitted only on COMPLETED CodeReview records (attempted on status %)', OLD.status;
        END IF;

        -- Rule 1.2: Before override, ALL THREE columns MUST be NULL (unassigned)
        IF (OLD.admin_override_reason IS NOT NULL OR
            OLD.admin_override_by_user_id IS NOT NULL OR
            OLD.admin_override_at IS NOT NULL) THEN
            RAISE EXCEPTION 'Admin override has already been assigned for CodeReview % and cannot be modified or cleared', OLD.id;
        END IF;

        -- Rule 1.3: A valid first override MUST populate ALL THREE columns atomically in the SAME UPDATE
        IF (NEW.admin_override_reason IS NULL OR
            NEW.admin_override_by_user_id IS NULL OR
            NEW.admin_override_at IS NULL) THEN
            RAISE EXCEPTION 'Admin override assignment for CodeReview % must populate admin_override_reason, admin_override_by_user_id, and admin_override_at atomically in a single UPDATE', OLD.id;
        END IF;
    END IF;

    -- Section 2: Check protected immutable fields when OLD.status is in terminal states
    IF OLD.status IN ('COMPLETED', 'FAILED', 'STALE', 'STALE_REVIEW', 'REJECTED_CONTEXT_TOO_LARGE') THEN
        IF (NEW.id IS DISTINCT FROM OLD.id OR
            NEW.project_id IS DISTINCT FROM OLD.project_id OR
            NEW.repository_id IS DISTINCT FROM OLD.repository_id OR
            NEW.task_id IS DISTINCT FROM OLD.task_id OR
            NEW.code_change_id IS DISTINCT FROM OLD.code_change_id OR
            NEW.review_mode IS DISTINCT FROM OLD.review_mode OR
            NEW.status IS DISTINCT FROM OLD.status OR
            NEW.summary IS DISTINCT FROM OLD.summary OR
            NEW.overall_risk_score IS DISTINCT FROM OLD.overall_risk_score OR
            NEW.review_context_hash IS DISTINCT FROM OLD.review_context_hash OR
            NEW.ai_input_hash IS DISTINCT FROM OLD.ai_input_hash OR
            NEW.model_identifier IS DISTINCT FROM OLD.model_identifier OR
            NEW.policy_version IS DISTINCT FROM OLD.policy_version OR
            NEW.reviewer_version IS DISTINCT FROM OLD.reviewer_version OR
            NEW.validator_version IS DISTINCT FROM OLD.validator_version OR
            NEW.prompt_version IS DISTINCT FROM OLD.prompt_version OR
            NEW.execution_time_ms IS DISTINCT FROM OLD.execution_time_ms OR
            NEW.created_at IS DISTINCT FROM OLD.created_at OR
            NEW.completed_at IS DISTINCT FROM OLD.completed_at) THEN
            RAISE EXCEPTION 'Attempted to modify protected immutable field on terminal CodeReview %', OLD.id;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prevent_code_review_unauthorized_update ON code_reviews;
CREATE TRIGGER trg_prevent_code_review_unauthorized_update
BEFORE UPDATE ON code_reviews
FOR EACH ROW EXECUTE FUNCTION prevent_code_review_unauthorized_update();

-- Database Level Immutability Trigger for review_findings (PostgreSQL 16)
CREATE OR REPLACE FUNCTION prevent_review_finding_protected_update()
RETURNS TRIGGER AS $$
BEGIN
    IF (NEW.id IS DISTINCT FROM OLD.id OR
        NEW.review_id IS DISTINCT FROM OLD.review_id OR
        NEW.project_id IS DISTINCT FROM OLD.project_id OR
        NEW.repository_id IS DISTINCT FROM OLD.repository_id OR
        NEW.task_id IS DISTINCT FROM OLD.task_id OR
        NEW.code_change_id IS DISTINCT FROM OLD.code_change_id OR
        NEW.finding_type IS DISTINCT FROM OLD.finding_type OR
        NEW.category IS DISTINCT FROM OLD.category OR
        NEW.cwe_id IS DISTINCT FROM OLD.cwe_id OR
        NEW.severity IS DISTINCT FROM OLD.severity OR
        NEW.confidence IS DISTINCT FROM OLD.confidence OR
        NEW.title IS DISTINCT FROM OLD.title OR
        NEW.description IS DISTINCT FROM OLD.description OR
        NEW.file_path IS DISTINCT FROM OLD.file_path OR
        NEW.start_line IS DISTINCT FROM OLD.start_line OR
        NEW.end_line IS DISTINCT FROM OLD.end_line OR
        NEW.side IS DISTINCT FROM OLD.side OR
        NEW.content_hash IS DISTINCT FROM OLD.content_hash OR
        NEW.fingerprint IS DISTINCT FROM OLD.fingerprint OR
        NEW.remediation_guidance IS DISTINCT FROM OLD.remediation_guidance OR
        NEW.created_at IS DISTINCT FROM OLD.created_at) THEN
        RAISE EXCEPTION 'Attempted to modify protected immutable field on ReviewFinding %', OLD.id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prevent_review_finding_protected_update ON review_findings;
CREATE TRIGGER trg_prevent_review_finding_protected_update
BEFORE UPDATE ON review_findings
FOR EACH ROW EXECUTE FUNCTION prevent_review_finding_protected_update();
