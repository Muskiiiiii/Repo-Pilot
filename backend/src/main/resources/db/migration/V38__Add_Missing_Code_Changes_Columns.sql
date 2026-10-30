-- Flyway Migration V38: Add missing CodeChange columns for JPA entity compatibility
ALTER TABLE code_changes
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS old_content TEXT,
    ADD COLUMN IF NOT EXISTS new_content TEXT,
    ADD COLUMN IF NOT EXISTS approved_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS approved_by UUID REFERENCES users(id) ON DELETE SET NULL;
