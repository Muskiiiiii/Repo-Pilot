-- Flyway Migration V29: Append-Only Trigger for audit_logs Table

-- 1. Create or replace trigger function to enforce database-level append-only invariant (reject UPDATE and DELETE)
CREATE OR REPLACE FUNCTION prevent_audit_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Audit log table is append-only. UPDATE and DELETE operations are forbidden.';
END;
$$ LANGUAGE plpgsql;

-- 2. Drop trigger if it already exists on audit_logs
DROP TRIGGER IF EXISTS trg_prevent_audit_logs_update_delete ON audit_logs;

-- 3. Attach append-only BEFORE UPDATE OR DELETE trigger to audit_logs table
CREATE TRIGGER trg_prevent_audit_logs_update_delete
BEFORE UPDATE OR DELETE ON audit_logs
FOR EACH ROW EXECUTE FUNCTION prevent_audit_modification();
