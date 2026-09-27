package com.repopilot.repository.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.ai.client.AiServiceClient;
import com.repopilot.audit.service.AuditLogService;
import com.repopilot.common.exception.ApiException;
import com.repopilot.github.dto.FileContentDto;
import com.repopilot.github.dto.FileTreeItemDto;
import com.repopilot.github.security.TokenEncryptionService;
import com.repopilot.github.service.GithubApiService;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.chunker.ChunkResult;
import com.repopilot.repository.dto.RepositoryIndexProgressDto;
import com.repopilot.repository.entity.*;
import com.repopilot.repository.repository.*;
import com.repopilot.user.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class RepositoryIndexingServiceImpl implements RepositoryIndexingService {

    private static final Logger logger = LoggerFactory.getLogger(RepositoryIndexingServiceImpl.class);

    private final RepositoryRepository repositoryRepository;
    private final RepositoryBranchRepository repositoryBranchRepository;
    private final RepositoryFileRepository repositoryFileRepository;
    private final CodeChunkRepository codeChunkRepository;
    private final EmbeddingRepository embeddingRepository;
    private final ProjectAuthorizationService authorizationService;
    private final GithubApiService githubApiService;
    private final TokenEncryptionService tokenEncryptionService;
    private final FileFilterService fileFilterService;
    private final CodeChunkerService codeChunkerService;
    private final AiServiceClient aiServiceClient;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final IndexingTransactionHelper transactionHelper;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Value("${repopilot.indexing.max-files-per-repo:1000}")
    private int maxFilesPerRepo;

    @Value("${repopilot.indexing.max-file-size-bytes:1048576}")
    private long maxSizeBytes;

    @Value("${repopilot.indexing.batch-size:100}")
    private int batchSize;

    @Value("${repopilot.github.concurrency:2}")
    private int githubConcurrency;

    @Value("${repopilot.indexing.embedding-model:voyage-code-4}")
    private String embeddingModel;

    @Value("${repopilot.indexing.embedding-dimensions:1024}")
    private int embeddingDimensions;

    public RepositoryIndexingServiceImpl(RepositoryRepository repositoryRepository,
                                         RepositoryBranchRepository repositoryBranchRepository,
                                         RepositoryFileRepository repositoryFileRepository,
                                         CodeChunkRepository codeChunkRepository,
                                         EmbeddingRepository embeddingRepository,
                                         ProjectAuthorizationService authorizationService,
                                         GithubApiService githubApiService,
                                         TokenEncryptionService tokenEncryptionService,
                                         FileFilterService fileFilterService,
                                         CodeChunkerService codeChunkerService,
                                         AiServiceClient aiServiceClient,
                                         AuditLogService auditLogService,
                                         ObjectMapper objectMapper) {
        this(repositoryRepository, repositoryBranchRepository, repositoryFileRepository,
             codeChunkRepository, embeddingRepository, authorizationService,
             githubApiService, tokenEncryptionService, fileFilterService,
             codeChunkerService, aiServiceClient, auditLogService, objectMapper, null);
    }

    @Autowired
    public RepositoryIndexingServiceImpl(RepositoryRepository repositoryRepository,
                                         RepositoryBranchRepository repositoryBranchRepository,
                                         RepositoryFileRepository repositoryFileRepository,
                                         CodeChunkRepository codeChunkRepository,
                                         EmbeddingRepository embeddingRepository,
                                         ProjectAuthorizationService authorizationService,
                                         GithubApiService githubApiService,
                                         TokenEncryptionService tokenEncryptionService,
                                         FileFilterService fileFilterService,
                                         CodeChunkerService codeChunkerService,
                                         AiServiceClient aiServiceClient,
                                         AuditLogService auditLogService,
                                         ObjectMapper objectMapper,
                                         IndexingTransactionHelper transactionHelper) {
        this.repositoryRepository = repositoryRepository;
        this.repositoryBranchRepository = repositoryBranchRepository;
        this.repositoryFileRepository = repositoryFileRepository;
        this.codeChunkRepository = codeChunkRepository;
        this.embeddingRepository = embeddingRepository;
        this.authorizationService = authorizationService;
        this.githubApiService = githubApiService;
        this.tokenEncryptionService = tokenEncryptionService;
        this.fileFilterService = fileFilterService;
        this.codeChunkerService = codeChunkerService;
        this.aiServiceClient = aiServiceClient;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.transactionHelper = (transactionHelper != null) ? transactionHelper :
                new IndexingTransactionHelper(repositoryRepository, repositoryBranchRepository, repositoryFileRepository, codeChunkRepository, embeddingRepository, auditLogService, objectMapper);
    }

    private static final String RENEW_LOCK_LUA =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
            "    return redis.call('PEXPIRE', KEYS[1], ARGV[2]) " +
            "else " +
            "    return 0 " +
            "end";

    private static final String RELEASE_LOCK_LUA =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
            "    return redis.call('DEL', KEYS[1]) " +
            "else " +
            "    return 0 " +
            "end";

    private static final long STALL_THRESHOLD_SECONDS = 120L;
    private static final double SLOW_RATE_THRESHOLD = 5.0;

    private static class ProgressSample {
        final long timestampMs;
        final int embeddedChunks;

        ProgressSample(long timestampMs, int embeddedChunks) {
            this.timestampMs = timestampMs;
            this.embeddedChunks = embeddedChunks;
        }
    }

    private static class IndexingSessionInfo {
        final long startMs;
        final int initialEmbeddedCount;

        IndexingSessionInfo(long startMs, int initialEmbeddedCount) {
            this.startMs = startMs;
            this.initialEmbeddedCount = initialEmbeddedCount;
        }
    }

    private final Map<UUID, Deque<ProgressSample>> progressHistory = new ConcurrentHashMap<>();
    private final Map<UUID, String> activeLocalWorkers = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> heartbeatTasks = new ConcurrentHashMap<>();
    private final Map<UUID, IndexingSessionInfo> indexingSessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatScheduler = Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r, "indexing-heartbeat-thread");
        t.setDaemon(true);
        return t;
    });

    @Override
    public RepositoryIndexProgressDto triggerIndexing(UUID projectId, UUID repositoryId, User currentUser, String branch) {
        Project project = authorizationService.getAuthorizedProject(projectId, currentUser);
        authorizationService.requireMinimumRole(project, currentUser, ProjectRole.MEMBER);

        Repository repository = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Repository not found"));

        if (!repository.getProject().getId().equals(projectId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Repository does not belong to specified project");
        }

        String targetBranch = (branch != null && !branch.trim().isEmpty()) ? branch : repository.getDefaultBranch();

        // Unified Redis Lock Check with Unique Per-Worker Token
        String lockKey = "indexing:lock:" + repositoryId;
        String workerToken = UUID.randomUUID().toString();

        if (!acquireRedisLock(lockKey, workerToken, Duration.ofMinutes(15))) {
            throw new ApiException(HttpStatus.CONFLICT, "INDEXING_IN_PROGRESS", "Repository indexing is already in progress");
        }

        // Clear any old cancel signal
        releaseRedisLockUnconditional("indexing:cancel:" + repositoryId);

        transactionHelper.updateRepositoryStatus(repositoryId, RepositoryIndexStatus.INDEXING);

        int currentDBEmbeddings = (int) embeddingRepository.countByRepositoryId(repositoryId);
        indexingSessions.put(repositoryId, new IndexingSessionInfo(System.currentTimeMillis(), currentDBEmbeddings));
        progressHistory.remove(repositoryId);

        startHeartbeat(repositoryId, workerToken, lockKey);
        activeLocalWorkers.put(repositoryId, workerToken);

        RepositoryIndexProgressDto initialProgress = createEnhancedProgress(
                repositoryId, RepositoryIndexStatus.INDEXING, 0, 0, 0, 0, null
        );
        updateProgressState(repositoryId, initialProgress);

        // Async execution
        CompletableFuture.runAsync(() -> {
            try {
                executeIndexingProcess(currentUser, repository, targetBranch, lockKey, workerToken);
            } catch (Exception e) {
                logger.error("Asynchronous indexing failed for repo {}: {}", repositoryId, e.getMessage(), e);
            }
        });

        return initialProgress;
    }

    @Override
    public RepositoryIndexProgressDto stopIndexing(UUID projectId, UUID repositoryId, User currentUser) {
        Project project = authorizationService.getAuthorizedProject(projectId, currentUser);
        authorizationService.requireMinimumRole(project, currentUser, ProjectRole.MEMBER);

        Repository repository = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Repository not found"));

        if (!repository.getProject().getId().equals(projectId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Repository does not belong to specified project");
        }

        String cancelKey = "indexing:cancel:" + repositoryId;
        String lockKey = "indexing:lock:" + repositoryId;

        boolean isActive = isLockedInRedis(lockKey) || activeLocalWorkers.containsKey(repositoryId);
        if (isActive) {
            // Signal active worker to stop
            acquireRedisLock(cancelKey, "CANCEL_SIGNAL", Duration.ofMinutes(15));
            logger.info("Cancellation signal sent for repository indexing: {}", repositoryId);
        } else {
            // No active worker running; transition DB status directly
            if (repository.getIndexStatus() == RepositoryIndexStatus.INDEXING) {
                transactionHelper.updateRepositoryStatus(repositoryId, RepositoryIndexStatus.CANCELLED);
                repository.setIndexStatus(RepositoryIndexStatus.CANCELLED);
            }
        }

        // Fetch counts from DB
        int tFiles = (int) repositoryFileRepository.countByRepositoryId(repositoryId);
        int tChunks = (int) codeChunkRepository.countByRepositoryId(repositoryId);
        int eChunks = (int) embeddingRepository.countByRepositoryId(repositoryId);

        RepositoryIndexProgressDto progress = createEnhancedProgress(
                repositoryId, RepositoryIndexStatus.CANCELLED, tFiles, tFiles, tChunks, eChunks, "Indexing cancelled by user"
        );
        updateProgressState(repositoryId, progress);
        return progress;
    }

    @Override
    public RepositoryIndexProgressDto resumeIndexing(UUID projectId, UUID repositoryId, User currentUser) {
        Project project = authorizationService.getAuthorizedProject(projectId, currentUser);
        authorizationService.requireMinimumRole(project, currentUser, ProjectRole.MEMBER);

        Repository repository = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Repository not found"));

        if (!repository.getProject().getId().equals(projectId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Repository does not belong to specified project");
        }

        String cancelKey = "indexing:cancel:" + repositoryId;
        releaseRedisLockUnconditional(cancelKey);

        return triggerIndexing(projectId, repositoryId, currentUser, repository.getDefaultBranch());
    }

    @Override
    public RepositoryIndexProgressDto getIndexingStatus(UUID projectId, UUID repositoryId, User currentUser) {
        Project project = authorizationService.getAuthorizedProject(projectId, currentUser);
        authorizationService.requireMinimumRole(project, currentUser, ProjectRole.VIEWER);

        Repository repository = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Repository not found"));

        if (!repository.getProject().getId().equals(projectId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Repository does not belong to specified project");
        }

        String lockKey = "indexing:lock:" + repositoryId;
        RepositoryIndexProgressDto cachedProgress = getProgressStateFromRedis(repositoryId);

        boolean isLocalActive = activeLocalWorkers.containsKey(repositoryId);
        boolean isLockPresent = isLockedInRedis(lockKey);
        boolean isProgressFresh = isProgressFresh(cachedProgress, STALL_THRESHOLD_SECONDS);

        if (cachedProgress != null) {
            if (cachedProgress.getStatus() == RepositoryIndexStatus.INDEXING
                    && !isLocalActive && !isLockPresent && !isProgressFresh) {

                logger.warn("Confirmed stale INDEXING progress cache in Redis for repo {} (no active worker, no lock, stale progress). Evicting stale cache.", repositoryId);
                evictProgressState(repositoryId);

                if (repository.getIndexStatus() == RepositoryIndexStatus.INDEXING) {
                    logger.warn("Repository {} status in DB is INDEXING but worker is confirmed terminated. Reconciling DB status to FAILED.", repositoryId);
                    transactionHelper.markIndexingFailed(repository, "Indexing process was interrupted or worker terminated unexpectedly", currentUser);
                    repository.setIndexStatus(RepositoryIndexStatus.FAILED);
                }
            } else if (repository.getIndexStatus() == RepositoryIndexStatus.INDEXED || cachedProgress.getStatus() == RepositoryIndexStatus.INDEXED) {
                int tFiles = (int) repositoryFileRepository.countByRepositoryId(repositoryId);
                int tChunks = (int) codeChunkRepository.countByRepositoryId(repositoryId);
                int eChunks = (int) embeddingRepository.countByRepositoryId(repositoryId);
                return createEnhancedProgress(
                        repositoryId, RepositoryIndexStatus.INDEXED, tFiles, tFiles, tChunks, eChunks, null
                );
            } else {
                return cachedProgress;
            }
        } else {
            if (repository.getIndexStatus() == RepositoryIndexStatus.INDEXING
                    && !isLocalActive && !isLockPresent) {

                logger.warn("Repository {} status in DB is INDEXING but worker is confirmed terminated (no lock, no progress). Reconciling DB status to FAILED.", repositoryId);
                transactionHelper.markIndexingFailed(repository, "Indexing process was interrupted or worker terminated unexpectedly", currentUser);
                repository.setIndexStatus(RepositoryIndexStatus.FAILED);
            }
        }

        // DB Fallback Status DTO computed from persisted entity counts
        int tFiles = (int) repositoryFileRepository.countByRepositoryId(repositoryId);
        int tChunks = (int) codeChunkRepository.countByRepositoryId(repositoryId);
        int eChunks = (int) embeddingRepository.countByRepositoryId(repositoryId);

        return createEnhancedProgress(
                repositoryId, repository.getIndexStatus(), tFiles, tFiles, tChunks, eChunks, null
        );
    }

    @Override
    public void executeIndexingProcess(User currentUser, Repository repository, String targetBranch, String lockKey) {
        String workerToken = getRedisLockOwner(lockKey);
        executeIndexingProcess(currentUser, repository, targetBranch, lockKey, workerToken);
    }

    public void executeIndexingProcess(User currentUser, Repository repository, String targetBranch, String lockKey, String workerToken) {
        UUID repositoryId = repository.getId();
        int totalCandidateFiles = 0;
        int processedFilesCount = 0;
        int totalChunksToEmbed = 0;
        int embeddedCount = 0;
        try {
            String accessToken = getDecryptedAccessTokenOrThrow(currentUser);

            List<FileTreeItemDto> treeItems = githubApiService.fetchRepositoryFileTree(accessToken, repository.getOwnerName(), repository.getRepoName(), targetBranch);

            // Filter candidate indexable files
            List<FileTreeItemDto> candidateFiles = new ArrayList<>();
            for (FileTreeItemDto item : treeItems) {
                if ("blob".equalsIgnoreCase(item.getType()) && fileFilterService.shouldIndexFile(item.getPath(), item.getSize() != null ? item.getSize() : 0, maxSizeBytes)) {
                    candidateFiles.add(item);
                }
            }

            // Check configurable limit requirement
            if (candidateFiles.size() > maxFilesPerRepo) {
                String errorMsg = "Repository exceeds maximum indexing limit of " + maxFilesPerRepo + " files (found " + candidateFiles.size() + ")";
                logger.error("Indexing rejected for repo {}: {}", repositoryId, errorMsg);
                markIndexingFailed(repository, errorMsg, currentUser);
                throw new ApiException(HttpStatus.BAD_REQUEST, "REPOSITORIES_EXCEED_LIMITS", errorMsg);
            }

            // Find or create RepositoryBranch entity (Transactional Helper Call)
            RepositoryBranch branchEntity = transactionHelper.findOrCreateBranch(repository, targetBranch);

            // Handle Incremental Indexing: Identify deleted files (Transactional Helper Calls)
            List<RepositoryFile> existingFiles = transactionHelper.getExistingFiles(repositoryId, branchEntity.getId());
            Set<String> activePathsInTree = candidateFiles.stream().map(FileTreeItemDto::getPath).collect(Collectors.toSet());

            for (RepositoryFile existingFile : existingFiles) {
                if (!activePathsInTree.contains(existingFile.getPath())) {
                    transactionHelper.removeDeletedFile(existingFile);
                }
            }

            // Fetch chunk IDs that already have embeddings for this repository in a single query
            Set<UUID> embeddedChunkIds = transactionHelper.getEmbeddedChunkIds(repositoryId);

            Map<String, RepositoryFile> existingFileMap = existingFiles.stream()
                    .collect(Collectors.toMap(RepositoryFile::getPath, f -> f, (a, b) -> a));

            // Fetch all existing chunks grouped by file ID in 1 DB query (eliminates N+1 DB round-trips)
            Map<UUID, List<CodeChunk>> existingChunksByFileId = transactionHelper.getExistingChunksGroupedByFile(repositoryId);

            // Pre-identify files that need content fetching from GitHub
            List<FileTreeItemDto> filesToFetch = new ArrayList<>();
            for (FileTreeItemDto item : candidateFiles) {
                RepositoryFile existingFile = existingFileMap.get(item.getPath());
                List<CodeChunk> existingChunks = (existingFile != null)
                        ? existingChunksByFileId.getOrDefault(existingFile.getId(), Collections.emptyList())
                        : Collections.emptyList();
                boolean isExistingIncomplete = existingFile != null && !existingFile.getIsBinary()
                        && !isLegitimatelyEmptyFile(existingFile) && existingChunks.isEmpty();

                boolean shaMatches = false;
                if (existingFile != null && item.getSha() != null && !item.getSha().isEmpty()) {
                    if (existingFile.getGithubBlobSha() != null) {
                        shaMatches = existingFile.getGithubBlobSha().equals(item.getSha());
                    } else if (!isExistingIncomplete || Boolean.TRUE.equals(existingFile.getIsBinary())) {
                        // Migration for legacy files: populate githubBlobSha without re-fetching content
                        existingFile.setGithubBlobSha(item.getSha());
                        transactionHelper.saveRepositoryFile(existingFile);
                        shaMatches = true;
                    }
                }

                if (!shaMatches || isExistingIncomplete) {
                    filesToFetch.add(item);
                }
            }

            // Bounded concurrent fetching of file contents for new/modified files
            Map<String, CompletableFuture<FileContentDto>> contentFutures = new HashMap<>();
            if (!filesToFetch.isEmpty()) {
                int poolSize = Math.min(Math.max(1, githubConcurrency), filesToFetch.size());
                java.util.concurrent.ExecutorService fetchExecutor = java.util.concurrent.Executors.newFixedThreadPool(poolSize);
                try {
                    for (FileTreeItemDto item : filesToFetch) {
                        CompletableFuture<FileContentDto> future = CompletableFuture.supplyAsync(() -> {
                            try {
                                return githubApiService.fetchFileContent(accessToken, repository.getOwnerName(), repository.getRepoName(), targetBranch, item.getPath());
                            } catch (ApiException e) {
                                if (e.getStatus() == HttpStatus.UNAUTHORIZED || e.getStatus() == HttpStatus.TOO_MANY_REQUESTS || e.getStatus() == HttpStatus.FORBIDDEN) {
                                    throw e;
                                }
                                logger.warn("File content fetch failed for path='{}': {}. Treating as unreadable file.", item.getPath(), e.getMessage());
                                return new FileContentDto(item.getPath(), targetBranch, "", "utf-8", 0L, true);
                            } catch (Exception e) {
                                logger.warn("Unexpected error fetching file content for path='{}': {}. Treating as unreadable file.", item.getPath(), e.getMessage());
                                return new FileContentDto(item.getPath(), targetBranch, "", "utf-8", 0L, true);
                            }
                        }, fetchExecutor);
                        contentFutures.put(item.getPath(), future);
                    }
                } finally {
                    fetchExecutor.shutdown();
                }
            }

            totalCandidateFiles = candidateFiles.size();
            processedFilesCount = 0;
            List<CodeChunk> chunksToEmbed = new ArrayList<>();

            for (FileTreeItemDto item : candidateFiles) {
                if (isCancellationRequested(repositoryId, lockKey, workerToken)) {
                    logger.info("Indexing cancellation observed for repository {}. Stopping gracefully.", repositoryId);
                    transactionHelper.updateRepositoryStatus(repositoryId, RepositoryIndexStatus.CANCELLED);
                    updateProgressState(repositoryId, createEnhancedProgress(
                            repositoryId, RepositoryIndexStatus.CANCELLED, totalCandidateFiles, processedFilesCount, chunksToEmbed.size(), 0, "Indexing cancelled by user"
                    ));
                    releaseRedisLockUnconditional("indexing:cancel:" + repositoryId);
                    return;
                }

                processedFilesCount++;
                RepositoryFile existingFile = existingFileMap.get(item.getPath());
                List<CodeChunk> existingChunks = (existingFile != null)
                        ? existingChunksByFileId.getOrDefault(existingFile.getId(), Collections.emptyList())
                        : Collections.emptyList();

                CompletableFuture<FileContentDto> future = contentFutures.get(item.getPath());

                if (future == null) {
                    // FAST PATH: Unchanged file with matching githubBlobSha and complete existing state. 0 network calls!
                    if (existingFile.getIsBinary()) {
                        if (existingChunks.isEmpty()) {
                            logger.debug("Incremental Indexing: Binary file '{}' is unchanged and has zero chunks. Skipping.", item.getPath());
                            updateProgressState(repositoryId, new RepositoryIndexProgressDto(
                                    repositoryId, RepositoryIndexStatus.INDEXING, totalCandidateFiles, processedFilesCount, chunksToEmbed.size(), 0, (double) processedFilesCount / totalCandidateFiles * 50.0, null
                            ));
                            continue;
                        } else {
                            logger.warn("Incremental Indexing Invariant Warning: File '{}' is flagged binary=true but has {} existing code chunks in DB. Checking for missing embeddings.", item.getPath(), existingChunks.size());
                        }
                    }

                    List<CodeChunk> unEmbeddedChunks = new ArrayList<>();
                    for (CodeChunk chunk : existingChunks) {
                        if (!embeddedChunkIds.contains(chunk.getId())) {
                            unEmbeddedChunks.add(chunk);
                        }
                    }

                    if (unEmbeddedChunks.isEmpty()) {
                        logger.debug("Incremental Indexing: File '{}' is unchanged and fully embedded. Skipping.", item.getPath());
                    } else {
                        logger.info("Incremental Indexing: File '{}' is unchanged, but has {} chunks missing embeddings. Queuing missing chunks.", item.getPath(), unEmbeddedChunks.size());
                        chunksToEmbed.addAll(unEmbeddedChunks);
                    }

                    updateProgressState(repositoryId, new RepositoryIndexProgressDto(
                            repositoryId, RepositoryIndexStatus.INDEXING, totalCandidateFiles, processedFilesCount, chunksToEmbed.size(), 0, (double) processedFilesCount / totalCandidateFiles * 50.0, null
                    ));
                    continue;
                }

                // File content was fetched concurrently
                FileContentDto contentDto;
                try {
                    contentDto = future.join();
                } catch (java.util.concurrent.CompletionException ce) {
                    if (ce.getCause() instanceof ApiException) {
                        throw (ApiException) ce.getCause();
                    } else if (ce.getCause() instanceof RuntimeException) {
                        throw (RuntimeException) ce.getCause();
                    } else {
                        throw new RuntimeException(ce.getCause());
                    }
                }

                String rawContent = contentDto.getContent() != null ? contentDto.getContent() : "";
                boolean isBinaryFile = (contentDto != null && Boolean.TRUE.equals(contentDto.getIsBinary()))
                        || rawContent.indexOf('\u0000') >= 0;

                String sha256Hash = CodeChunkerService.computeSha256(rawContent);
                String githubBlobSha = item.getSha();
                boolean isExistingIncomplete = existingFile != null && !existingFile.getIsBinary()
                        && !isLegitimatelyEmptyFile(existingFile) && existingChunks.isEmpty();

                // Check if fetched content matches existing hash
                if (existingFile != null && !isExistingIncomplete
                        && sha256Hash.equals(existingFile.getContentHash())
                        && Objects.equals(existingFile.getIsBinary(), isBinaryFile)) {

                    existingFile.setGithubBlobSha(githubBlobSha);
                    transactionHelper.saveRepositoryFile(existingFile);

                    if (isBinaryFile) {
                        if (existingChunks.isEmpty()) {
                            logger.debug("Incremental Indexing: Binary file '{}' is unchanged and has zero chunks. Skipping.", item.getPath());
                            updateProgressState(repositoryId, new RepositoryIndexProgressDto(
                                    repositoryId, RepositoryIndexStatus.INDEXING, totalCandidateFiles, processedFilesCount, chunksToEmbed.size(), 0, (double) processedFilesCount / totalCandidateFiles * 50.0, null
                            ));
                            continue;
                        } else {
                            logger.warn("Incremental Indexing Invariant Warning: File '{}' is flagged binary=true but has {} existing code chunks in DB. Checking for missing embeddings.", item.getPath(), existingChunks.size());
                        }
                    }

                    List<CodeChunk> unEmbeddedChunks = new ArrayList<>();
                    for (CodeChunk chunk : existingChunks) {
                        if (!embeddedChunkIds.contains(chunk.getId())) {
                            unEmbeddedChunks.add(chunk);
                        }
                    }

                    if (unEmbeddedChunks.isEmpty()) {
                        logger.debug("Incremental Indexing: File '{}' is unchanged and fully embedded. Skipping.", item.getPath());
                    } else {
                        logger.info("Incremental Indexing: File '{}' is unchanged, but has {} chunks missing embeddings. Queuing missing chunks.", item.getPath(), unEmbeddedChunks.size());
                        chunksToEmbed.addAll(unEmbeddedChunks);
                    }

                    updateProgressState(repositoryId, new RepositoryIndexProgressDto(
                            repositoryId, RepositoryIndexStatus.INDEXING, totalCandidateFiles, processedFilesCount, chunksToEmbed.size(), 0, (double) processedFilesCount / totalCandidateFiles * 50.0, null
                    ));
                    continue;
                }

                // Chunk file content if non-binary (OUTSIDE DB TRANSACTION)
                List<ChunkResult> chunkResults = null;
                String language = fileFilterService.detectLanguage(item.getPath());
                if (!isBinaryFile) {
                    chunkResults = codeChunkerService.chunkFile(item.getPath(), rawContent, language);
                }

                long fileSize = contentDto.getSize() != null ? contentDto.getSize() : (long) rawContent.length();

                // Atomic save/replace file and chunks (Transactional Helper Call)
                SavedFileAndChunks savedResult = transactionHelper.saveFileAndChunks(
                        repository, branchEntity, item.getPath(), language, fileSize,
                        sha256Hash, githubBlobSha, isBinaryFile, existingFile, chunkResults
                );

                if (isBinaryFile) {
                    logger.info("File '{}' is binary or contains NUL bytes. Metadata saved, skipping chunking and embeddings.", item.getPath());
                    updateProgressState(repositoryId, new RepositoryIndexProgressDto(
                            repositoryId, RepositoryIndexStatus.INDEXING, totalCandidateFiles, processedFilesCount, chunksToEmbed.size(), 0, (double) processedFilesCount / totalCandidateFiles * 50.0, null
                    ));
                    continue;
                }

                chunksToEmbed.addAll(savedResult.getChunks());

                updateProgressState(repositoryId, new RepositoryIndexProgressDto(
                        repositoryId, RepositoryIndexStatus.INDEXING, totalCandidateFiles, processedFilesCount, chunksToEmbed.size(), 0, (double) processedFilesCount / totalCandidateFiles * 50.0, null
                ));
            }

            // Batch Embeddings Execution
            totalChunksToEmbed = chunksToEmbed.size();
            embeddedCount = 0;

            for (int i = 0; i < totalChunksToEmbed; i += batchSize) {
                if (isCancellationRequested(repositoryId, lockKey, workerToken)) {
                    logger.info("Indexing cancellation observed for repository {}. Stopping gracefully.", repositoryId);
                    transactionHelper.updateRepositoryStatus(repositoryId, RepositoryIndexStatus.CANCELLED);
                    RepositoryIndexProgressDto cancelProgress = createEnhancedProgress(
                            repositoryId, RepositoryIndexStatus.CANCELLED, totalCandidateFiles, totalCandidateFiles, totalChunksToEmbed, embeddedCount, "Indexing cancelled by user"
                    );
                    updateProgressState(repositoryId, cancelProgress);
                    releaseRedisLockUnconditional("indexing:cancel:" + repositoryId);
                    return;
                }

                int end = Math.min(i + batchSize, totalChunksToEmbed);
                List<CodeChunk> batch = chunksToEmbed.subList(i, end);

                List<String> batchTexts = new ArrayList<>();
                for (CodeChunk c : batch) {
                    String formattedContext = "File: " + c.getFile().getPath() + " | Symbol: " + (c.getSymbolName() != null ? c.getSymbolName() : "") + "\n" + c.getContent();
                    int estTokens = CodeChunkerService.estimateTokens(formattedContext);
                    if (estTokens > 2000 || formattedContext.length() > 6000) {
                        throw new IllegalStateException(
                                "Oversized chunk encountered during batch preparation for chunk " + c.getId() + " in file '" + c.getFile().getPath() + "': " + estTokens + " tokens / " + formattedContext.length() + " chars vs limit 2000 / 6000"
                        );
                    }
                    batchTexts.add(formattedContext);
                }

                // Network API call for embeddings (OUTSIDE DB TRANSACTION)
                List<String> pgVectorStrings = aiServiceClient.generateEmbeddings(batchTexts);

                // Save embeddings batch (Transactional Helper Call)
                transactionHelper.saveEmbeddingsBatch(batch, pgVectorStrings, embeddingModel, embeddingDimensions);

                embeddedCount += batch.size();
                updateProgressState(repositoryId, createEnhancedProgress(
                        repositoryId, RepositoryIndexStatus.INDEXING, totalCandidateFiles, totalCandidateFiles, totalChunksToEmbed, embeddedCount, null
                ));
            }

            // Strong Completion Safety Guard: Verify no unembedded CodeChunks remain in DB before finalizing INDEXED status
            Set<UUID> remainingUnembeddedInRepo = transactionHelper.getUnembeddedChunkIds(repositoryId);
            if (!remainingUnembeddedInRepo.isEmpty()) {
                long totalDBChunks = codeChunkRepository.countByRepositoryId(repositoryId);
                String errorMsg = "Cannot mark repository as INDEXED: " + remainingUnembeddedInRepo.size() + " of " + totalDBChunks + " code chunks are missing embeddings.";
                logger.error("Completion guard failed for repository {}: {}", repositoryId, errorMsg);
                markIndexingFailed(repository, errorMsg, currentUser, totalCandidateFiles, totalCandidateFiles, totalChunksToEmbed, embeddedCount);
                return;
            }

            // Finalize Indexing Success (Transactional Helper Call)
            transactionHelper.markIndexingSuccess(repository, totalCandidateFiles, embeddedCount, currentUser);

            int repoTotalFiles = (int) repositoryFileRepository.countByRepositoryId(repositoryId);
            int repoTotalChunks = (int) codeChunkRepository.countByRepositoryId(repositoryId);
            int repoEmbeddedChunks = (int) embeddingRepository.countByRepositoryId(repositoryId);

            updateProgressState(repositoryId, createEnhancedProgress(
                    repositoryId, RepositoryIndexStatus.INDEXED, repoTotalFiles, repoTotalFiles, repoTotalChunks, repoEmbeddedChunks, null
            ));

        } catch (ApiException e) {
            logger.error("Indexing API exception for repo {}: {}", repositoryId, e.getMessage());
            markIndexingFailed(repository, e.getMessage(), currentUser, totalCandidateFiles, processedFilesCount, totalChunksToEmbed, embeddedCount);
            throw e;
        } catch (Exception e) {
            logger.error("Error during repository indexing for repo {}: {}", repositoryId, e.getMessage(), e);
            markIndexingFailed(repository, e.getMessage(), currentUser, totalCandidateFiles, processedFilesCount, totalChunksToEmbed, embeddedCount);
        } finally {
            stopHeartbeat(repositoryId);
            activeLocalWorkers.remove(repositoryId);
            if (workerToken != null) {
                releaseRedisLock(lockKey, workerToken);
            }
        }
    }

    private void markIndexingFailed(Repository repository, String errorMessage, User currentUser) {
        markIndexingFailed(repository, errorMessage, currentUser, 0, 0, 0, 0);
    }

    private void markIndexingFailed(Repository repository, String errorMessage, User currentUser, int tFiles, int pFiles, int tChunks, int eChunks) {
        RepositoryIndexProgressDto currentCached = getProgressStateFromRedis(repository.getId());
        int safeTFiles = tFiles > 0 ? tFiles : (currentCached != null && currentCached.getTotalFiles() > 0 ? currentCached.getTotalFiles() : 0);
        int safePFiles = pFiles > 0 ? pFiles : (currentCached != null && currentCached.getProcessedFiles() > 0 ? currentCached.getProcessedFiles() : 0);
        int safeTChunks = tChunks > 0 ? tChunks : (currentCached != null && currentCached.getTotalChunks() > 0 ? currentCached.getTotalChunks() : 0);
        int safeEChunks = eChunks > 0 ? eChunks : (currentCached != null && currentCached.getEmbeddedChunks() > 0 ? currentCached.getEmbeddedChunks() : 0);

        updateProgressState(repository.getId(), createEnhancedProgress(
                repository.getId(), RepositoryIndexStatus.FAILED, safeTFiles, safePFiles, safeTChunks, safeEChunks, errorMessage
        ));

        transactionHelper.markIndexingFailed(repository, errorMessage, currentUser);
    }

    private String getDecryptedAccessTokenOrThrow(User user) {
        String encrypted = user.getGithubAccessToken();
        if (encrypted == null || encrypted.isEmpty()) {
            return "";
        }
        try {
            String token = tokenEncryptionService.decrypt(encrypted);
            return token != null ? token : "";
        } catch (Exception e) {
            logger.warn("Failed to decrypt user GitHub token. Falling back to public request.");
            return "";
        }
    }

    private boolean isCancellationRequested(UUID repositoryId, String lockKey, String workerToken) {
        if (isLockedInRedis("indexing:cancel:" + repositoryId)) {
            return true;
        }
        if (workerToken != null && redisTemplate != null && !workerToken.equals(getRedisLockOwner(lockKey))) {
            logger.warn("Indexing process for repo {} observed lock ownership loss. Worker token {} != lock owner {}", repositoryId, workerToken, getRedisLockOwner(lockKey));
            return true;
        }
        return false;
    }

    private void startHeartbeat(UUID repositoryId, String workerToken, String lockKey) {
        stopHeartbeat(repositoryId);
        ScheduledFuture<?> future = heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                boolean renewed = renewRedisLock(lockKey, workerToken, Duration.ofMinutes(15));
                if (renewed) {
                    logger.debug("Heartbeat renewed lock for repo {} with token {}", repositoryId, workerToken);
                } else {
                    logger.warn("Heartbeat failed to renew lock for repo {} (lock lost or owned by another worker)", repositoryId);
                }
            } catch (Throwable t) {
                logger.error("Error in heartbeat for repo {}: {}", repositoryId, t.getMessage());
            }
        }, 60, 60, TimeUnit.SECONDS);
        heartbeatTasks.put(repositoryId, future);
    }

    private void stopHeartbeat(UUID repositoryId) {
        ScheduledFuture<?> future = heartbeatTasks.remove(repositoryId);
        if (future != null) {
            future.cancel(true);
        }
    }

    private RepositoryIndexProgressDto createEnhancedProgress(
            UUID repositoryId,
            RepositoryIndexStatus status,
            int totalFiles,
            int processedFiles,
            int totalChunks,
            int embeddedChunks,
            String errorMessage) {

        int remainingEmbeddings = Math.max(0, totalChunks - embeddedChunks);
        double pct;
        if (status == RepositoryIndexStatus.INDEXED) {
            pct = 100.0;
        } else if (totalChunks > 0) {
            pct = (double) embeddedChunks / totalChunks * 100.0;
        } else if (totalFiles > 0) {
            pct = (double) processedFiles / totalFiles * 50.0;
        } else {
            pct = 0.0;
        }

        String nowIso = Instant.now().toString();
        long nowMs = System.currentTimeMillis();

        Double ratePerMinute = null;
        Long estimatedRemainingSeconds = null;

        if (status == RepositoryIndexStatus.INDEXING) {
            IndexingSessionInfo session = indexingSessions.get(repositoryId);
            if (session != null) {
                long elapsedSec = (nowMs - session.startMs) / 1000;
                int newEmbeddings = embeddedChunks - session.initialEmbeddedCount;

                // Minimum 30s elapsed window required to prevent false rate spikes
                if (elapsedSec >= 30 && newEmbeddings > 0) {
                    ratePerMinute = ((double) newEmbeddings / elapsedSec) * 60.0;
                    if (ratePerMinute > 0 && remainingEmbeddings > 0) {
                        estimatedRemainingSeconds = (long) Math.ceil((double) remainingEmbeddings / (ratePerMinute / 60.0));
                    }
                }
            } else {
                Deque<ProgressSample> samples = progressHistory.computeIfAbsent(repositoryId, k -> new ConcurrentLinkedDeque<>());
                samples.addLast(new ProgressSample(nowMs, embeddedChunks));

                while (!samples.isEmpty() && (nowMs - samples.peekFirst().timestampMs > 300_000)) {
                    samples.pollFirst();
                }

                if (samples.size() >= 2) {
                    ProgressSample oldest = samples.peekFirst();
                    ProgressSample newest = samples.peekLast();
                    long elapsedSec = (newest.timestampMs - oldest.timestampMs) / 1000;
                    int delta = newest.embeddedChunks - oldest.embeddedChunks;

                    if (elapsedSec >= 30 && delta > 0) {
                        ratePerMinute = ((double) delta / elapsedSec) * 60.0;
                        if (ratePerMinute > 0 && remainingEmbeddings > 0) {
                            estimatedRemainingSeconds = (long) Math.ceil((double) remainingEmbeddings / (ratePerMinute / 60.0));
                        }
                    }
                }
            }
        }

        String health;
        if (status == RepositoryIndexStatus.INDEXED) {
            health = "READY";
        } else if (status == RepositoryIndexStatus.CANCELLED) {
            health = "STOPPED";
        } else if (status == RepositoryIndexStatus.FAILED) {
            health = "FAILED";
        } else if (status == RepositoryIndexStatus.INDEXING) {
            IndexingSessionInfo session = indexingSessions.get(repositoryId);
            long timeSinceStartSec = session != null ? (nowMs - session.startMs) / 1000 : 0;
            if (timeSinceStartSec > STALL_THRESHOLD_SECONDS && ratePerMinute != null && ratePerMinute == 0.0) {
                health = "STALLED";
            } else if (ratePerMinute != null && ratePerMinute < SLOW_RATE_THRESHOLD && ratePerMinute > 0) {
                health = "SLOW";
            } else {
                health = "HEALTHY";
            }
        } else {
            health = "NOT_INDEXED";
        }

        boolean canStop = (status == RepositoryIndexStatus.INDEXING);
        boolean canResume = (status == RepositoryIndexStatus.FAILED || status == RepositoryIndexStatus.CANCELLED || status == RepositoryIndexStatus.NOT_INDEXED);

        return new RepositoryIndexProgressDto(
                repositoryId, status, totalFiles, processedFiles, totalChunks, embeddedChunks,
                remainingEmbeddings, pct, ratePerMinute, estimatedRemainingSeconds, nowIso,
                health, canStop, canResume, errorMessage
        );
    }

    public boolean isLockedInRedis(String key) {
        if (redisTemplate == null) return false;
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            logger.warn("Redis unavailable during lock check: {}", e.getMessage());
            return false;
        }
    }

    public String getRedisLockOwner(String key) {
        if (redisTemplate == null) return null;
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            logger.warn("Redis unavailable during lock owner check: {}", e.getMessage());
            return null;
        }
    }

    public boolean acquireRedisLock(String key, String workerToken, Duration ttl) {
        if (redisTemplate == null) return true;
        try {
            Boolean success = redisTemplate.opsForValue().setIfAbsent(key, workerToken, ttl);
            return Boolean.TRUE.equals(success);
        } catch (Exception e) {
            logger.warn("Redis unavailable during lock acquisition: {}", e.getMessage());
            return true;
        }
    }

    public boolean renewRedisLock(String key, String workerToken, Duration ttl) {
        if (redisTemplate == null) return true;
        try {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(RENEW_LOCK_LUA, Long.class);
            Long result = redisTemplate.execute(script, Collections.singletonList(key), workerToken, String.valueOf(ttl.toMillis()));
            return result != null && result > 0;
        } catch (Exception e) {
            logger.warn("Redis unavailable during lock renewal: {}", e.getMessage());
            return false;
        }
    }

    public boolean releaseRedisLock(String key, String workerToken) {
        if (redisTemplate == null) return true;
        try {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(RELEASE_LOCK_LUA, Long.class);
            Long result = redisTemplate.execute(script, Collections.singletonList(key), workerToken);
            return result != null && result > 0;
        } catch (Exception e) {
            logger.warn("Redis unavailable during lock release: {}", e.getMessage());
            return false;
        }
    }

    public void releaseRedisLockUnconditional(String key) {
        if (redisTemplate == null) return;
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            logger.warn("Redis unavailable during unconditional lock release: {}", e.getMessage());
        }
    }

    private boolean isProgressFresh(RepositoryIndexProgressDto progress, long maxAgeSeconds) {
        if (progress == null || progress.getLastProgressAt() == null) return false;
        try {
            Instant timestamp = Instant.parse(progress.getLastProgressAt());
            long ageSec = Duration.between(timestamp, Instant.now()).getSeconds();
            return Math.abs(ageSec) < maxAgeSeconds;
        } catch (Exception e) {
            return false;
        }
    }

    private void updateProgressState(UUID repositoryId, RepositoryIndexProgressDto dto) {
        if (redisTemplate == null) return;
        try {
            String json = objectMapper.writeValueAsString(dto);
            redisTemplate.opsForValue().set("indexing:progress:" + repositoryId, json, Duration.ofHours(24));
        } catch (Exception e) {
            logger.warn("Redis unavailable during progress update: {}", e.getMessage());
        }
    }

    private RepositoryIndexProgressDto getProgressStateFromRedis(UUID repositoryId) {
        if (redisTemplate == null) return null;
        try {
            String json = redisTemplate.opsForValue().get("indexing:progress:" + repositoryId);
            if (json != null) {
                return objectMapper.readValue(json, RepositoryIndexProgressDto.class);
            }
        } catch (Exception e) {
            logger.warn("Redis unavailable during progress fetch: {}", e.getMessage());
        }
        return null;
    }

    private void evictProgressState(UUID repositoryId) {
        if (redisTemplate == null) return;
        try {
            redisTemplate.delete("indexing:progress:" + repositoryId);
        } catch (Exception e) {
            logger.warn("Redis unavailable during progress eviction: {}", e.getMessage());
        }
    }

    private boolean isLegitimatelyEmptyFile(RepositoryFile existingFile) {
        if (existingFile == null) return false;
        if (existingFile.getFileSize() != null && existingFile.getFileSize() == 0L) return true;
        String hash = existingFile.getContentHash();
        if (hash == null) return false;
        return "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855".equalsIgnoreCase(hash)
                || "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391".equalsIgnoreCase(hash)
                || "4b825dc642cb6eb9a060e54bf8d69288fbee4904".equalsIgnoreCase(hash);
    }
}
