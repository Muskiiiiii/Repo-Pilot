package com.repopilot.repository.repository;

import com.repopilot.repository.entity.CodeChunk;
import com.repopilot.repository.entity.Embedding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface EmbeddingRepository extends JpaRepository<Embedding, UUID> {

    List<Embedding> findByChunk(CodeChunk chunk);

    Optional<Embedding> findByChunkId(UUID chunkId);

    @Modifying
    @Transactional
    void deleteByChunkId(UUID chunkId);

    @Modifying
    @Transactional
    void deleteByChunkIn(List<CodeChunk> chunks);

    @Query("SELECT e.chunk.id FROM Embedding e WHERE e.chunk.file.repository.id = :repositoryId")
    Set<UUID> findChunkIdsWithEmbeddingsByRepositoryId(@Param("repositoryId") UUID repositoryId);

    @Query("SELECT COUNT(e) FROM Embedding e WHERE e.chunk.file.repository.id = :repositoryId")
    long countByRepositoryId(@Param("repositoryId") UUID repositoryId);

    @Query(value = """
        SELECT c.id AS chunk_id,
               c.chunk_index AS chunk_index,
               c.content AS content,
               c.symbol_name AS symbol_name,
               c.chunk_type AS chunk_type,
               c.start_line AS start_line,
               c.end_line AS end_line,
               f.path AS file_path,
               f.language AS file_language,
               (e.embedding <=> CAST(:queryVector AS vector)) AS distance
        FROM embeddings e
        JOIN code_chunks c ON e.chunk_id = c.id
        JOIN repository_files f ON c.file_id = f.id
        WHERE f.repository_id = :repositoryId
        ORDER BY (e.embedding <=> CAST(:queryVector AS vector)) ASC
        LIMIT :topK
        """, nativeQuery = true)
    List<Object[]> searchSemanticChunksNative(
        @Param("repositoryId") UUID repositoryId,
        @Param("queryVector") String queryVector,
        @Param("topK") int topK
    );
}
