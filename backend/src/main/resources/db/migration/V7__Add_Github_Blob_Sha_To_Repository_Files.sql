-- Migration V7: Add github_blob_sha column to repository_files table
ALTER TABLE repository_files ADD COLUMN github_blob_sha VARCHAR(255);
