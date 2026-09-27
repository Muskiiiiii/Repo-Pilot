-- Migration V4: Add GitHub OAuth fields to users table

ALTER TABLE users
ADD COLUMN IF NOT EXISTS github_user_id VARCHAR(255),
ADD COLUMN IF NOT EXISTS github_username VARCHAR(255),
ADD COLUMN IF NOT EXISTS github_access_token VARCHAR(512),
ADD COLUMN IF NOT EXISTS github_connected_at TIMESTAMP WITH TIME ZONE;
