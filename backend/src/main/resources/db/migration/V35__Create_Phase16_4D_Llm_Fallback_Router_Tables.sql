-- Flyway Migration V35: Create Phase 16.4D LLM Fallback Router & Policy Tables

-- 1. Immutable Exceptional Fallback Policy Versions Table
CREATE TABLE llm_exceptional_fallback_policies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    source_authority_type VARCHAR(64) NOT NULL,
    original_provider_id VARCHAR(32) NOT NULL,
    target_provider_id VARCHAR(32) NOT NULL,
    policy_version INTEGER NOT NULL DEFAULT 1,
    created_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    reason TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_llm_exceptional_policy_ver UNIQUE (organization_id, source_authority_type, original_provider_id, target_provider_id, policy_version),
    CONSTRAINT uk_llm_exceptional_policy_identity UNIQUE (id, organization_id, source_authority_type, original_provider_id, target_provider_id),
    CONSTRAINT uk_llm_exceptional_policy_id_org UNIQUE (id, organization_id)
);

CREATE INDEX idx_llm_exceptional_policy_lookup
    ON llm_exceptional_fallback_policies(organization_id, source_authority_type, original_provider_id, policy_version);

-- DB-Level Immutability Trigger for Exceptional Policy Version Rows
CREATE OR REPLACE FUNCTION trg_prevent_exceptional_policy_update()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'LlmExceptionalFallbackPolicy rows are immutable. Create a new policy_version instead of updating existing records.';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_prevent_exceptional_policy_update_trigger
BEFORE UPDATE ON llm_exceptional_fallback_policies
FOR EACH ROW
EXECUTE FUNCTION trg_prevent_exceptional_policy_update();

-- 2. Active Exceptional Fallback Policy Pointer Table
CREATE TABLE llm_active_exceptional_fallback_policies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    source_authority_type VARCHAR(64) NOT NULL,
    original_provider_id VARCHAR(32) NOT NULL,
    target_provider_id VARCHAR(32) NOT NULL,
    active_policy_id UUID NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_llm_active_exceptional_policy UNIQUE (organization_id, source_authority_type, original_provider_id, target_provider_id),
    CONSTRAINT fk_active_policy_identity FOREIGN KEY (active_policy_id, organization_id, source_authority_type, original_provider_id, target_provider_id)
        REFERENCES llm_exceptional_fallback_policies(id, organization_id, source_authority_type, original_provider_id, target_provider_id) ON DELETE RESTRICT
);

CREATE INDEX idx_llm_active_policy_lookup
    ON llm_active_exceptional_fallback_policies(organization_id, source_authority_type, original_provider_id);

-- 3. Add Composite Unique Constraints to existing Phase 16.4 tables for tenancy and logical request consistency
ALTER TABLE llm_logical_requests
ADD CONSTRAINT uk_llm_logical_id_org UNIQUE (id, organization_id);

ALTER TABLE llm_provider_attempts
ADD CONSTRAINT uk_llm_attempt_id_logical UNIQUE (id, logical_request_id);

-- 4. Fallback Decisions Table
CREATE TABLE llm_fallback_decisions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    logical_request_id UUID NOT NULL,
    source_attempt_id UUID NOT NULL,
    decision_sequence INTEGER NOT NULL,
    target_attempt_id UUID NULL,
    original_provider_id VARCHAR(32) NOT NULL,
    original_model_id VARCHAR(64) NOT NULL,
    target_provider_id VARCHAR(32) NULL,
    target_model_id VARCHAR(64) NULL,
    decision_status VARCHAR(32) NOT NULL,
    trigger_safety_classification VARCHAR(32) NOT NULL,
    reservation_id UUID NULL,
    exceptional_policy_id UUID NULL,
    rejection_reason VARCHAR(64) NULL,
    fencing_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE NULL,
    CONSTRAINT uk_fallback_decision_trigger UNIQUE (source_attempt_id, decision_sequence),
    CONSTRAINT uk_fallback_decision_target UNIQUE (target_attempt_id),
    CONSTRAINT fk_fallback_decision_logical_org FOREIGN KEY (logical_request_id, organization_id)
        REFERENCES llm_logical_requests(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_fallback_decision_source_attempt_logical FOREIGN KEY (source_attempt_id, logical_request_id)
        REFERENCES llm_provider_attempts(id, logical_request_id) ON DELETE RESTRICT,
    CONSTRAINT fk_fallback_decision_target_attempt_logical FOREIGN KEY (target_attempt_id, logical_request_id)
        REFERENCES llm_provider_attempts(id, logical_request_id) ON DELETE RESTRICT,
    CONSTRAINT fk_fallback_decision_exceptional_policy_org FOREIGN KEY (exceptional_policy_id, organization_id)
        REFERENCES llm_exceptional_fallback_policies(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT chk_fallback_decision_status CHECK (
        decision_status IN (
            'DECISION_INITIATED',
            'CAPACITY_RESERVED',
            'TARGET_ATTEMPT_CREATED',
            'EXECUTING_TARGET_ATTEMPT',
            'COMPLETED_SUCCESS',
            'COMPLETED_FAILED',
            'BLOCKED_POLICY',
            'RECONCILIATION_REQUIRED'
        )
    ),
    CONSTRAINT chk_fallback_target_attempt CHECK (
        (decision_status IN ('DECISION_INITIATED', 'CAPACITY_RESERVED', 'BLOCKED_POLICY', 'RECONCILIATION_REQUIRED') AND target_attempt_id IS NULL)
        OR
        (decision_status IN ('TARGET_ATTEMPT_CREATED', 'EXECUTING_TARGET_ATTEMPT', 'COMPLETED_SUCCESS', 'COMPLETED_FAILED') AND target_attempt_id IS NOT NULL)
    )
);

CREATE INDEX idx_llm_fallback_logical ON llm_fallback_decisions(logical_request_id);
CREATE INDEX idx_llm_fallback_source ON llm_fallback_decisions(source_attempt_id);
CREATE INDEX idx_llm_fallback_status ON llm_fallback_decisions(decision_status);

-- 5. Add fallback_decision_id column & 1:1 unique constraint to llm_provider_attempts
ALTER TABLE llm_provider_attempts
ADD COLUMN IF NOT EXISTS fallback_decision_id UUID NULL REFERENCES llm_fallback_decisions(id) ON DELETE RESTRICT;

CREATE UNIQUE INDEX uk_llm_attempt_fallback_decision ON llm_provider_attempts(fallback_decision_id) WHERE fallback_decision_id IS NOT NULL;
