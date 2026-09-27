-- Flyway Migration V34: Create Phase 16.4 LLM Fallback, Attempt, Pricing, Audit Outbox & Overage Tables

-- 1. Logical LLM Requests Table
CREATE TABLE llm_logical_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    source_authority_type VARCHAR(64) NOT NULL,
    source_authority_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    feature_name VARCHAR(64) NOT NULL,
    execution_status VARCHAR(32) NOT NULL,
    billing_status VARCHAR(32) NOT NULL,
    current_attempt_count INTEGER NOT NULL DEFAULT 0,
    fencing_version BIGINT NOT NULL DEFAULT 1,
    total_tokens_used INTEGER NOT NULL DEFAULT 0,
    total_billed_cost_micro_usd BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE NULL,
    CONSTRAINT uk_llm_logical_source UNIQUE (source_authority_type, source_authority_id, idempotency_key)
);

CREATE INDEX idx_llm_logical_org ON llm_logical_requests(organization_id);
CREATE INDEX idx_llm_logical_project ON llm_logical_requests(project_id);

-- 2. Pricing Versions Table
CREATE TABLE llm_pricing_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id VARCHAR(32) NOT NULL,
    model_id VARCHAR(64) NOT NULL,
    pricing_version INTEGER NOT NULL,
    input_rate_micro_usd_per_1k BIGINT NOT NULL,
    output_rate_micro_usd_per_1k BIGINT NOT NULL,
    effective_from TIMESTAMP WITH TIME ZONE NOT NULL,
    effective_until TIMESTAMP WITH TIME ZONE NULL,
    CONSTRAINT uk_llm_pricing_ver UNIQUE (provider_id, model_id, pricing_version)
);

-- 3. Provider Attempts Table
CREATE TABLE llm_provider_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    logical_request_id UUID NOT NULL REFERENCES llm_logical_requests(id) ON DELETE CASCADE,
    attempt_number INTEGER NOT NULL,
    provider_id VARCHAR(32) NOT NULL,
    model_id VARCHAR(64) NOT NULL,
    pricing_version_id UUID NOT NULL REFERENCES llm_pricing_versions(id) ON DELETE RESTRICT,
    provider_idempotency_key VARCHAR(128) NOT NULL,
    provider_request_id VARCHAR(128) NULL,
    worker_lease_token VARCHAR(128) NULL,
    fencing_version BIGINT NOT NULL DEFAULT 1,
    claim_expires_at TIMESTAMP WITH TIME ZONE NULL,
    input_hash VARCHAR(64) NOT NULL,
    output_hash VARCHAR(64) NULL,
    prompt_tokens INTEGER NOT NULL DEFAULT 0,
    completion_tokens INTEGER NOT NULL DEFAULT 0,
    total_tokens INTEGER NOT NULL DEFAULT 0,
    estimated_cost_micro_usd BIGINT NOT NULL DEFAULT 0,
    calculated_cost_micro_usd BIGINT NOT NULL DEFAULT 0,
    execution_status VARCHAR(32) NOT NULL,
    billing_status VARCHAR(32) NOT NULL,
    fallback_reason VARCHAR(64) NULL,
    trace_id VARCHAR(32) NULL,
    span_id VARCHAR(16) NULL,
    start_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    end_time TIMESTAMP WITH TIME ZONE NULL,
    latency_ms BIGINT NULL,
    CONSTRAINT uk_llm_attempt_seq UNIQUE (logical_request_id, attempt_number)
);

CREATE INDEX idx_llm_attempts_logical ON llm_provider_attempts(logical_request_id);

-- 4. Audit Outbox Table
CREATE TABLE audit_log_outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    canonical_audit_event_id VARCHAR(128) NOT NULL UNIQUE,
    occurrence_id UUID NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_json TEXT NOT NULL,
    delivery_state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    retry_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP WITH TIME ZONE NULL
);

-- 5. Organization Overage Liabilities Table
CREATE TABLE organization_overage_liabilities (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    attempt_id UUID NOT NULL REFERENCES llm_provider_attempts(id) ON DELETE CASCADE,
    financial_liability_micro_usd BIGINT NOT NULL,
    reason VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_org_overage_org ON organization_overage_liabilities(organization_id);

-- 6. Update Organization Usage Counters for Hard Reservations & Unresolved Exposure
ALTER TABLE organization_usage_counters
ADD COLUMN IF NOT EXISTS reserved_tokens INTEGER NOT NULL DEFAULT 0,
ADD COLUMN IF NOT EXISTS reserved_spend_micro_usd BIGINT NOT NULL DEFAULT 0,
ADD COLUMN IF NOT EXISTS unresolved_exposure_tokens INTEGER NOT NULL DEFAULT 0,
ADD COLUMN IF NOT EXISTS unresolved_exposure_spend_micro_usd BIGINT NOT NULL DEFAULT 0;
