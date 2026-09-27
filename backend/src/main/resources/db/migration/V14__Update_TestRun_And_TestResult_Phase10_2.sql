-- Flyway Migration V14: Phase 10.2 Test Evidence & Resource Observability Extensions

ALTER TABLE test_runs
    ADD COLUMN IF NOT EXISTS termination_reason VARCHAR(50) DEFAULT 'NORMAL_EXIT',
    ADD COLUMN IF NOT EXISTS stdout_bytes_observed BIGINT DEFAULT 0,
    ADD COLUMN IF NOT EXISTS stderr_bytes_observed BIGINT DEFAULT 0,
    ADD COLUMN IF NOT EXISTS tests_run INT DEFAULT 0,
    ADD COLUMN IF NOT EXISTS failures_count INT DEFAULT 0,
    ADD COLUMN IF NOT EXISTS errors_count INT DEFAULT 0,
    ADD COLUMN IF NOT EXISTS skipped_count INT DEFAULT 0,
    ADD COLUMN IF NOT EXISTS passed_count INT DEFAULT 0,
    ADD COLUMN IF NOT EXISTS parser_status VARCHAR(50) DEFAULT 'UNPARSED',
    ADD COLUMN IF NOT EXISTS failure_evidence JSONB;
