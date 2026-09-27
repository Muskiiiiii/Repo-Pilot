-- Flyway Migration V25: Update CodeReview Uniqueness Constraint for Phase 13 Slice 4

ALTER TABLE code_reviews DROP CONSTRAINT IF EXISTS uk_code_reviews_change_hash;
ALTER TABLE code_reviews DROP CONSTRAINT IF EXISTS uk_code_reviews_change_context_mode_ai_hash;

ALTER TABLE code_reviews ADD CONSTRAINT uk_code_reviews_change_context_mode_ai_hash UNIQUE (code_change_id, review_context_hash, review_mode, ai_input_hash);
