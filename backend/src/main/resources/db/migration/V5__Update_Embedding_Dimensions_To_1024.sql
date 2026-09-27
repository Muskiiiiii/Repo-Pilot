-- Migration V5: Update embedding vector dimension from 1536 to 1024 for Voyage AI (voyage-code-4)
-- RATIONALE FOR REMOVING EXISTING EMBEDDINGS:
-- Vector spaces of different dimensions (1536 vs 1024) and embedding providers are mathematically incompatible.
-- Existing 1536d vectors cannot be converted or reused with 1024d Voyage AI models.
-- PostgreSQL pgvector requires the column to be cleared before altering the column vector dimension type.

-- Reset index status of any previously INDEXED or INDEXING repositories to NOT_INDEXED
-- so that the system accurately reflects that embeddings are missing and re-indexing is required.
UPDATE repositories SET index_status = 'NOT_INDEXED' WHERE index_status IN ('INDEXED', 'INDEXING');

-- Remove existing incompatible 1536-dimensional vector embeddings
TRUNCATE TABLE embeddings CASCADE;

-- Alter column default and vector type to 1024 dimensions
ALTER TABLE embeddings ALTER COLUMN dimensions SET DEFAULT 1024;
ALTER TABLE embeddings ALTER COLUMN embedding TYPE vector(1024);
