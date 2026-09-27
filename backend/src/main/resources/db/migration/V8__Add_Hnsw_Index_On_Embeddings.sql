-- Migration V8: Add HNSW index on embeddings vector column for fast pgvector cosine similarity search
CREATE INDEX IF NOT EXISTS idx_embeddings_hnsw ON embeddings USING hnsw (embedding vector_cosine_ops);
