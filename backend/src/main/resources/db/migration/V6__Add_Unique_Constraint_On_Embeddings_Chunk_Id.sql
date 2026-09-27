-- Migration V6: Ensure unique constraint on embeddings.chunk_id
ALTER TABLE embeddings ADD CONSTRAINT uk_embeddings_chunk_id UNIQUE (chunk_id);
