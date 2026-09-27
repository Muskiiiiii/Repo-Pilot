-- Flyway Migration V23: Append-Only Trigger and Retention-Safe Foreign Keys for Publication Audit Events

-- 1. Drop existing foreign key constraints on publication_audit_events
ALTER TABLE publication_audit_events DROP CONSTRAINT IF EXISTS publication_audit_events_project_id_fkey;
ALTER TABLE publication_audit_events DROP CONSTRAINT IF EXISTS publication_audit_events_repository_id_fkey;
ALTER TABLE publication_audit_events DROP CONSTRAINT IF EXISTS publication_audit_events_task_id_fkey;
ALTER TABLE publication_audit_events DROP CONSTRAINT IF EXISTS publication_audit_events_code_change_id_fkey;

-- 2. Drop NOT NULL constraints on foreign key columns to allow SET NULL upon parent entity deletion
ALTER TABLE publication_audit_events ALTER COLUMN project_id DROP NOT NULL;
ALTER TABLE publication_audit_events ALTER COLUMN repository_id DROP NOT NULL;
ALTER TABLE publication_audit_events ALTER COLUMN task_id DROP NOT NULL;
ALTER TABLE publication_audit_events ALTER COLUMN code_change_id DROP NOT NULL;

-- 3. Re-add foreign key constraints with ON DELETE SET NULL to preserve audit history when parent entities are purged
ALTER TABLE publication_audit_events ADD CONSTRAINT fk_pub_audit_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE SET NULL;
ALTER TABLE publication_audit_events ADD CONSTRAINT fk_pub_audit_repository FOREIGN KEY (repository_id) REFERENCES repositories(id) ON DELETE SET NULL;
ALTER TABLE publication_audit_events ADD CONSTRAINT fk_pub_audit_task FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE SET NULL;
ALTER TABLE publication_audit_events ADD CONSTRAINT fk_pub_audit_code_change FOREIGN KEY (code_change_id) REFERENCES code_changes(id) ON DELETE SET NULL;

-- 4. Create trigger function to enforce database-level append-only invariant (reject UPDATE and DELETE)
CREATE OR REPLACE FUNCTION prevent_audit_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'publication_audit_events table is append-only. UPDATE and DELETE operations are forbidden.';
END;
$$ LANGUAGE plpgsql;

-- 5. Attach trigger to publication_audit_events table
DROP TRIGGER IF EXISTS trg_prevent_audit_update_delete ON publication_audit_events;

CREATE TRIGGER trg_prevent_audit_update_delete
BEFORE UPDATE OR DELETE ON publication_audit_events
FOR EACH ROW EXECUTE FUNCTION prevent_audit_modification();
