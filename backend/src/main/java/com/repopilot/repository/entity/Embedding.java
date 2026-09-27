package com.repopilot.repository.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnTransformer;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "embeddings", uniqueConstraints = {
        @UniqueConstraint(name = "uk_embeddings_chunk_id", columnNames = {"chunk_id"})
})
public class Embedding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chunk_id", nullable = false)
    private CodeChunk chunk;

    @Column(name = "model_name", nullable = false)
    private String modelName;

    @Column(nullable = false)
    private Integer dimensions = 1024;

    @Column(name = "embedding", columnDefinition = "vector")
    @ColumnTransformer(read = "embedding::text", write = "CAST(? AS vector)")
    private String embedding;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Embedding() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public CodeChunk getChunk() {
        return chunk;
    }

    public void setChunk(CodeChunk chunk) {
        this.chunk = chunk;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public Integer getDimensions() {
        return dimensions;
    }

    public void setDimensions(Integer dimensions) {
        this.dimensions = dimensions;
    }

    public String getEmbedding() {
        return embedding;
    }

    public void setEmbedding(String embedding) {
        this.embedding = embedding;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
