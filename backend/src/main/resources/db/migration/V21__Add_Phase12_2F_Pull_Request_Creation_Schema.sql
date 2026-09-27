-- Flyway Migration V21: Phase 12.2-F-D Pull Request Creation Columns

ALTER TABLE pull_request_publications
    ADD COLUMN IF NOT EXISTS github_pr_number BIGINT,
    ADD COLUMN IF NOT EXISTS pr_url TEXT;

CREATE INDEX IF NOT EXISTS idx_pr_pub_pr_number ON pull_request_publications(repository_id, github_pr_number);
