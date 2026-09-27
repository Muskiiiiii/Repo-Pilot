-- Migration: Add generation_token field to tasks table for concurrent code generation serialization
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS generation_token VARCHAR(64);
