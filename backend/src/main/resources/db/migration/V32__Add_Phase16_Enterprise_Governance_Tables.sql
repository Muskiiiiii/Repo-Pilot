-- Flyway Migration V32: Add Phase 16 Enterprise Governance Tables and Tenancy

-- 1. Organizations Table
CREATE TABLE organizations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 2. Organization Members Table
CREATE TABLE organization_members (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(50) NOT NULL DEFAULT 'ORG_MEMBER',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_org_members_org_user UNIQUE (organization_id, user_id)
);

CREATE INDEX idx_org_members_org ON organization_members(organization_id);
CREATE INDEX idx_org_members_user ON organization_members(user_id);

-- 3. Organization Policies Table
CREATE TABLE organization_policies (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    organization_id UUID NOT NULL UNIQUE REFERENCES organizations(id) ON DELETE CASCADE,
    monthly_token_budget BIGINT NOT NULL DEFAULT 1000000,
    monthly_spend_limit_usd NUMERIC(12,2) NOT NULL DEFAULT 100.00,
    allowed_llm_models_json JSONB NOT NULL DEFAULT '["claude-3-5-sonnet", "gpt-4o"]'::jsonb,
    enforce_security_review BOOLEAN NOT NULL DEFAULT true,
    max_concurrent_executions INT NOT NULL DEFAULT 5,
    audit_retention_days INT NOT NULL DEFAULT 90,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 4. Organization Usage Counters Table
CREATE TABLE organization_usage_counters (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    organization_id UUID NOT NULL UNIQUE REFERENCES organizations(id) ON DELETE CASCADE,
    tokens_consumed BIGINT NOT NULL DEFAULT 0,
    total_spend_cents BIGINT NOT NULL DEFAULT 0,
    period_start TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 5. Audit Export Jobs Table
CREATE TABLE audit_export_jobs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    requested_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    date_from TIMESTAMP WITH TIME ZONE NOT NULL,
    date_to TIMESTAMP WITH TIME ZONE NOT NULL,
    max_audit_id UUID,
    s3_key VARCHAR(512),
    download_url VARCHAR(1024),
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_export_jobs_org ON audit_export_jobs(organization_id);
CREATE INDEX idx_audit_export_jobs_status ON audit_export_jobs(status);

-- 6. Seed Default Organization
INSERT INTO organizations (id, name, slug, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000001', 'Default Organization', 'default-org', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

INSERT INTO organization_policies (organization_id)
VALUES ('00000000-0000-0000-0000-000000000001')
ON CONFLICT (organization_id) DO NOTHING;

INSERT INTO organization_usage_counters (organization_id)
VALUES ('00000000-0000-0000-0000-000000000001')
ON CONFLICT (organization_id) DO NOTHING;

-- 7. Add organization_id to projects
ALTER TABLE projects ADD COLUMN organization_id UUID REFERENCES organizations(id);

-- Assign existing projects to Default Organization
UPDATE projects SET organization_id = '00000000-0000-0000-0000-000000000001' WHERE organization_id IS NULL;

-- Enforce NOT NULL on projects.organization_id
ALTER TABLE projects ALTER COLUMN organization_id SET NOT NULL;
CREATE INDEX idx_projects_organization ON projects(organization_id);

-- 8. Add project owners & members to organization_members (Additive)
INSERT INTO organization_members (organization_id, user_id, role)
SELECT DISTINCT organization_id, owner_id, 'ORG_ADMIN'
FROM projects
ON CONFLICT (organization_id, user_id) DO NOTHING;

INSERT INTO organization_members (organization_id, user_id, role)
SELECT DISTINCT p.organization_id, pm.user_id, 'ORG_MEMBER'
FROM project_members pm
JOIN projects p ON pm.project_id = p.id
ON CONFLICT (organization_id, user_id) DO NOTHING;

-- 9. Add organization_id to audit_logs and migrate legacy audit records
-- Temporarily disable append-only trigger for migration update
ALTER TABLE audit_logs DISABLE TRIGGER trg_prevent_audit_logs_update_delete;

ALTER TABLE audit_logs ADD COLUMN organization_id UUID REFERENCES organizations(id);

-- Step 9.1: Direct project association
UPDATE audit_logs a
SET organization_id = p.organization_id
FROM projects p
WHERE a.project_id = p.id AND a.organization_id IS NULL;

-- Step 9.2: Task project association
UPDATE audit_logs a
SET organization_id = p.organization_id
FROM tasks t
JOIN projects p ON t.project_id = p.id
WHERE a.task_id = t.id AND a.organization_id IS NULL;

-- Step 9.3: Single organization user membership
UPDATE audit_logs a
SET organization_id = sub.org_id
FROM (
    SELECT user_id, MIN(organization_id) AS org_id
    FROM organization_members
    GROUP BY user_id
    HAVING COUNT(DISTINCT organization_id) = 1
) sub
WHERE a.user_id = sub.user_id AND a.organization_id IS NULL;

-- Step 9.4: Orphan fallback to Default Organization
UPDATE audit_logs
SET organization_id = '00000000-0000-0000-0000-000000000001'
WHERE organization_id IS NULL;

ALTER TABLE audit_logs ALTER COLUMN organization_id SET NOT NULL;
CREATE INDEX idx_audit_logs_org ON audit_logs(organization_id);

-- Re-enable append-only trigger on audit_logs
ALTER TABLE audit_logs ENABLE TRIGGER trg_prevent_audit_logs_update_delete;

-- 10. Direct SQL Immutability Trigger on projects.organization_id
CREATE OR REPLACE FUNCTION trg_prevent_project_org_update()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.organization_id IS DISTINCT FROM NEW.organization_id THEN
        RAISE EXCEPTION 'projects.organization_id is immutable once assigned';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_enforce_project_org_immutability ON projects;

CREATE TRIGGER trg_enforce_project_org_immutability
BEFORE UPDATE ON projects
FOR EACH ROW
EXECUTE FUNCTION trg_prevent_project_org_update();
