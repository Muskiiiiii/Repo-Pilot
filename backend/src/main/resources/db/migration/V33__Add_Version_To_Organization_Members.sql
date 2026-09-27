-- Flyway Migration V33: Add version column to organization_members for JPA @Version optimistic locking
ALTER TABLE organization_members ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
