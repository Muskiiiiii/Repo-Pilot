package com.repopilot.repository.repository;

import com.repopilot.repository.entity.CodeChunk;
import com.repopilot.repository.entity.RepositoryFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Repository
public interface CodeChunkRepository extends JpaRepository<CodeChunk, UUID> {

    List<CodeChunk> findByFile(RepositoryFile file);

    List<CodeChunk> findByFileId(UUID fileId);

    @Query("SELECT c FROM CodeChunk c JOIN FETCH c.file WHERE c.file.id = :fileId")
    List<CodeChunk> findByFileIdWithFile(@Param("fileId") UUID fileId);

    @Query("SELECT c FROM CodeChunk c JOIN FETCH c.file WHERE c.file.repository.id = :repositoryId")
    List<CodeChunk> findByRepositoryIdWithFile(@Param("repositoryId") UUID repositoryId);

    @Query("SELECT c FROM CodeChunk c WHERE c.file.repository.id = :repositoryId AND c.file.path = :filePath ORDER BY c.chunkIndex ASC")
    List<CodeChunk> findByRepositoryIdAndFilePath(@Param("repositoryId") UUID repositoryId, @Param("filePath") String filePath);

    long countByFileId(UUID fileId);

    @Modifying
    @Transactional
    void deleteByFileId(UUID fileId);

    @Modifying
    @Transactional
    void deleteByFileIn(List<RepositoryFile> files);

    @Query("SELECT COUNT(c) FROM CodeChunk c WHERE c.file.repository.id = :repositoryId")
    long countByRepositoryId(@Param("repositoryId") UUID repositoryId);
}
