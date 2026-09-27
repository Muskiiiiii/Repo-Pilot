-- V37__Audit_Outbox_Organization_Ownership.sql
-- Forward-only migration establishing authoritative organization ownership for audit_log_outbox.

-- 1. Add organization_id column referencing organizations(id) with ON DELETE RESTRICT semantics
ALTER TABLE audit_log_outbox ADD COLUMN IF NOT EXISTS organization_id UUID REFERENCES organizations(id) ON DELETE RESTRICT;

-- 2. Authoritative relational backfill for pre-existing audit_log_outbox rows

-- Path 1: Aggregate is FALLBACK_DECISION -> llm_fallback_decisions -> llm_logical_requests.organization_id
UPDATE audit_log_outbox o
SET organization_id = lr.organization_id
FROM llm_fallback_decisions fd
JOIN llm_logical_requests lr ON fd.logical_request_id = lr.id
WHERE o.organization_id IS NULL
  AND o.aggregate_type = 'FALLBACK_DECISION'
  AND o.aggregate_id = fd.id::text;

-- Path 2: Aggregate is LlmExceptionalFallbackPolicy -> llm_exceptional_fallback_policies.organization_id
UPDATE audit_log_outbox o
SET organization_id = p.organization_id
FROM llm_exceptional_fallback_policies p
WHERE o.organization_id IS NULL
  AND o.aggregate_type = 'LlmExceptionalFallbackPolicy'
  AND o.aggregate_id = p.id::text;

-- Path 3: Aggregate is LlmActiveExceptionalFallbackPolicy -> aggregate_id IS organizations.id
UPDATE audit_log_outbox o
SET organization_id = org.id
FROM organizations org
WHERE o.organization_id IS NULL
  AND o.aggregate_type = 'LlmActiveExceptionalFallbackPolicy'
  AND o.aggregate_id = org.id::text;

-- Path 4: Aggregate is AuditExportJob -> audit_export_jobs.organization_id
UPDATE audit_log_outbox o
SET organization_id = j.organization_id
FROM audit_export_jobs j
WHERE o.organization_id IS NULL
  AND o.aggregate_type = 'AuditExportJob'
  AND o.aggregate_id = j.id::text;

-- 3. Fail-closed check: if any pre-existing row remains unmapped, raise exception
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM audit_log_outbox WHERE organization_id IS NULL) THEN
        RAISE EXCEPTION 'Backfill failed closed: Found audit_log_outbox rows with unresolvable organization ownership';
    END IF;
END $$;

-- 4. Create composite index for organization-scoped sequence lookup
CREATE INDEX IF NOT EXISTS idx_audit_log_outbox_org_seq ON audit_log_outbox(organization_id, sequence_number);
