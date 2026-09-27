package com.repopilot.repository.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.audit.service.AuditLogService;
import com.repopilot.repository.chunker.ChunkResult;
import com.repopilot.repository.entity.*;
import com.repopilot.repository.repository.*;
import com.repopilot.user.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Component
public class IndexingTransactionHelper {

    private static final Logger logger = LoggerFactory.getLogger(IndexingTransactionHelper.class);

    private final RepositoryRepository repositoryRepository;
    private final RepositoryBranchRepository repositoryBranchRepository;
    private final RepositoryFileRepository repositoryFileRepository;
    private final CodeChunkRepository codeChunkRepository;
    private final EmbeddingRepository embeddingRepository;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public IndexingTransactionHelper(RepositoryRepository repositoryRepository,
                                     RepositoryBranchRepository repositoryBranchRepository,
                                     RepositoryFileRepository repositoryFileRepository,
                                     CodeChunkRepository codeChunkRepository,
                                     EmbeddingRepository embeddingRepository,
                                     AuditLogService auditLogService,
                                     ObjectMapper objectMapper) {
        this.repositoryRepository = repositoryRepository;
        this.repositoryBranchRepository = repositoryBranchRepository;
        this.repositoryFileRepository = repositoryFileRepository;
        this.codeChunkRepository = codeChunkRepository;
        this.embeddingRepository = embeddingRepository;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Set<UUID> getEmbeddedChunkIds(UUID repositoryId) {
        Set<UUID> ids = embeddingRepository.findChunkIdsWithEmbeddingsByRepositoryId(repositoryId);
        return (ids != null) ? ids : Collections.emptySet();
    }

    @Transactional(readOnly = true)
    public Set<UUID> getUnembeddedChunkIds(UUID repositoryId) {
        List<CodeChunk> allChunks = codeChunkRepository.findByRepositoryIdWithFile(repositoryId);
        Set<UUID> embeddedIds = getEmbeddedChunkIds(repositoryId);
        if (allChunks == null || allChunks.isEmpty()) {
            return Collections.emptySet();
        }
        Set<UUID> unembeddedIds = new HashSet<>();
        for (CodeChunk c : allChunks) {
            if (!embeddedIds.contains(c.getId())) {
                unembeddedIds.add(c.getId());
            }
        }
        return unembeddedIds;
    }

    @Transactional(readOnly = true)
    public List<CodeChunk> getExistingChunksForFile(UUID fileId) {
        List<CodeChunk> chunks = codeChunkRepository.findByFileIdWithFile(fileId);
        return (chunks != null) ? chunks : Collections.emptyList();
    }

    @Transactional(readOnly = true)
    public Map<UUID, List<CodeChunk>> getExistingChunksGroupedByFile(UUID repositoryId) {
        List<CodeChunk> chunks = codeChunkRepository.findByRepositoryIdWithFile(repositoryId);
        if (chunks == null || chunks.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<UUID, List<CodeChunk>> result = new HashMap<>();
        for (CodeChunk chunk : chunks) {
            result.computeIfAbsent(chunk.getFile().getId(), k -> new ArrayList<>()).add(chunk);
        }
        return result;
    }

    @Transactional
    public void updateRepositoryStatus(UUID repositoryId, RepositoryIndexStatus status) {
        Repository repository = repositoryRepository.findById(repositoryId).orElse(null);
        if (repository != null) {
            repository.setIndexStatus(status);
            repositoryRepository.save(repository);
        }
    }

    @Transactional
    public RepositoryBranch findOrCreateBranch(Repository repository, String targetBranch) {
        return repositoryBranchRepository.findByRepositoryIdAndName(repository.getId(), targetBranch)
                .orElseGet(() -> {
                    RepositoryBranch rb = new RepositoryBranch();
                    rb.setRepository(repository);
                    rb.setName(targetBranch);
                    rb.setCommitSha("head");
                    rb.setIsDefault(targetBranch.equalsIgnoreCase(repository.getDefaultBranch()));
                    return repositoryBranchRepository.save(rb);
                });
    }

    @Transactional(readOnly = true)
    public List<RepositoryFile> getExistingFiles(UUID repositoryId, UUID branchId) {
        return repositoryFileRepository.findByRepositoryIdAndBranchId(repositoryId, branchId);
    }

    @Transactional
    public void removeDeletedFile(RepositoryFile existingFile) {
        logger.info("Incremental Indexing: Removing deleted file '{}'", existingFile.getPath());
        List<CodeChunk> chunks = getExistingChunksForFile(existingFile.getId());
        if (!chunks.isEmpty()) {
            embeddingRepository.deleteByChunkIn(chunks);
            codeChunkRepository.deleteByFileIn(List.of(existingFile));
        }
        repositoryFileRepository.delete(existingFile);
    }

    @Transactional
    public RepositoryFile saveRepositoryFile(RepositoryFile file) {
        return repositoryFileRepository.save(file);
    }

    @Transactional
    public SavedFileAndChunks saveFileAndChunks(Repository repository,
                                                RepositoryBranch branchEntity,
                                                String path,
                                                String language,
                                                long fileSize,
                                                String contentHash,
                                                String githubBlobSha,
                                                boolean isBinaryFile,
                                                RepositoryFile existingFile,
                                                List<ChunkResult> chunkResults) {
        RepositoryFile fileToSave = (existingFile != null) ? existingFile : new RepositoryFile();
        fileToSave.setRepository(repository);
        fileToSave.setBranch(branchEntity);
        fileToSave.setPath(path);
        fileToSave.setLanguage(language);
        fileToSave.setFileSize(fileSize);
        fileToSave.setContentHash(contentHash);
        fileToSave.setGithubBlobSha(githubBlobSha);
        fileToSave.setIsBinary(isBinaryFile);
        fileToSave.setIsSensitive(false);

        RepositoryFile savedFile = repositoryFileRepository.save(fileToSave);

        // Clean old chunks and embeddings if modifying an existing file
        if (existingFile != null) {
            List<CodeChunk> oldChunks = getExistingChunksForFile(savedFile.getId());
            if (!oldChunks.isEmpty()) {
                embeddingRepository.deleteByChunkIn(oldChunks);
                codeChunkRepository.deleteByFileIn(List.of(savedFile));
            }
        }

        List<CodeChunk> savedChunks = new ArrayList<>();
        if (!isBinaryFile && chunkResults != null) {
            for (ChunkResult cr : chunkResults) {
                CodeChunk chunk = new CodeChunk();
                chunk.setFile(savedFile);
                chunk.setChunkIndex(cr.getChunkIndex());
                chunk.setContent(cr.getContent());
                chunk.setSymbolName(cr.getSymbolName());
                chunk.setChunkType(cr.getChunkType());
                chunk.setStartLine(cr.getStartLine());
                chunk.setEndLine(cr.getEndLine());
                chunk.setContentHash(cr.getContentHash());

                // Defensive validation before saving chunk to DB
                String formatted = "File: " + savedFile.getPath() + " | Symbol: " + (cr.getSymbolName() != null ? cr.getSymbolName() : "") + "\n" + cr.getContent();
                int estTokens = CodeChunkerService.estimateTokens(formatted);
                if (estTokens > 2000 || formatted.length() > 6000) {
                    throw new IllegalStateException("Attempted to persist oversized chunk for file '" + savedFile.getPath() + "': " + estTokens + " tokens / " + formatted.length() + " chars vs limit 2000 / 6000");
                }

                CodeChunk savedChunk = codeChunkRepository.save(chunk);
                if (!cr.isSensitive()) {
                    savedChunks.add(savedChunk);
                }
            }
        }

        return new SavedFileAndChunks(savedFile, savedChunks);
    }

    @Transactional
    public void saveEmbeddingsBatch(List<CodeChunk> batch, List<String> pgVectorStrings, String modelName, int dimensions) {
        for (int j = 0; j < batch.size(); j++) {
            CodeChunk chunk = batch.get(j);
            String vectorStr = pgVectorStrings.get(j);

            Embedding embedding = new Embedding();
            embedding.setChunk(chunk);
            embedding.setModelName(modelName);
            embedding.setDimensions(dimensions);
            embedding.setEmbedding(vectorStr);
            embeddingRepository.save(embedding);
        }
    }

    @Transactional
    public void markIndexingSuccess(Repository repository, int totalCandidateFiles, int embeddedCount, User currentUser) {
        long totalChunks = codeChunkRepository.countByRepositoryId(repository.getId());
        if (totalChunks > 0) {
            Set<UUID> embeddedChunkIds = embeddingRepository.findChunkIdsWithEmbeddingsByRepositoryId(repository.getId());
            if (embeddedChunkIds.size() < totalChunks) {
                long missingCount = totalChunks - embeddedChunkIds.size();
                throw new IllegalStateException("Cannot mark repository as INDEXED: " + missingCount + " of " + totalChunks + " code chunks are missing embeddings.");
            }
        }

        Repository repoToUpdate = repositoryRepository.findById(repository.getId()).orElse(repository);
        repoToUpdate.setIndexStatus(RepositoryIndexStatus.INDEXED);
        repoToUpdate.setLastIndexedAt(Instant.now());
        repositoryRepository.save(repoToUpdate);

        auditLogService.logAction(
                currentUser, repoToUpdate.getProject(), null,
                "REPOSITORY_INDEXED", "REPOSITORY", repoToUpdate.getId(),
                "{\"indexedFiles\":" + totalCandidateFiles + ",\"embeddedChunks\":" + embeddedCount + "}"
        );

        logger.info("Repository {} indexing successfully completed. Indexed {} files, {} chunks.", repoToUpdate.getId(), totalCandidateFiles, embeddedCount);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markIndexingFailed(Repository repository, String errorMessage, User currentUser) {
        try {
            Repository repoToUpdate = repositoryRepository.findById(repository.getId()).orElse(repository);
            repoToUpdate.setIndexStatus(RepositoryIndexStatus.FAILED);
            repositoryRepository.save(repoToUpdate);

            String metadataJson = null;
            if (errorMessage != null) {
                try {
                    metadataJson = objectMapper.writeValueAsString(Map.of("error", errorMessage));
                } catch (Exception e) {
                    logger.warn("Failed to format error JSON for audit log: {}", e.getMessage());
                    metadataJson = "{\"error\":\"Indexing failed\"}";
                }
            }

            auditLogService.logAction(
                    currentUser, repoToUpdate.getProject(), null,
                    "REPOSITORY_INDEX_FAILED", "REPOSITORY", repoToUpdate.getId(),
                    metadataJson
            );
        } catch (Exception e) {
            logger.error("Failed to execute markIndexingFailed cleanly for repo {}: {}", repository.getId(), e.getMessage());
        }
    }
}
