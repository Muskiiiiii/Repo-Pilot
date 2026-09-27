package com.repopilot.change.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.ai.client.AiServiceClient;
import com.repopilot.change.dto.CodeChangeResponseDto;
import com.repopilot.change.dto.CodeChangeSetResponseDto;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.common.exception.ApiException;
import com.repopilot.plan.entity.Plan;
import com.repopilot.plan.repository.PlanRepository;
import com.repopilot.project.entity.Project;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.entity.CodeChunk;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.entity.RepositoryIndexStatus;
import com.repopilot.repository.repository.CodeChunkRepository;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.security.service.SecretScannerService;
import com.repopilot.task.dto.EvidenceReferenceDto;
import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskStatus;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.user.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CodeGenerationServiceImpl implements CodeGenerationService {

    private static final Logger logger = LoggerFactory.getLogger(CodeGenerationServiceImpl.class);

    private final ProjectAuthorizationService projectAuthorizationService;
    private final RepositoryRepository repositoryRepository;
    private final TaskRepository taskRepository;
    private final PlanRepository planRepository;
    private final CodeChangeRepository codeChangeRepository;
    private final CodeChunkRepository codeChunkRepository;
    private final AiServiceClient aiServiceClient;
    private final DiffGeneratorService diffGeneratorService;
    private final SecretScannerService secretScannerService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @Value("${repopilot.repository.storage-path:/app/data/repositories}")
    private String baseStoragePath;

    @org.springframework.beans.factory.annotation.Autowired
    public CodeGenerationServiceImpl(ProjectAuthorizationService projectAuthorizationService,
                                     RepositoryRepository repositoryRepository,
                                     TaskRepository taskRepository,
                                     PlanRepository planRepository,
                                     CodeChangeRepository codeChangeRepository,
                                     CodeChunkRepository codeChunkRepository,
                                     AiServiceClient aiServiceClient,
                                     DiffGeneratorService diffGeneratorService,
                                     SecretScannerService secretScannerService,
                                     ObjectMapper objectMapper,
                                     PlatformTransactionManager transactionManager) {
        this.projectAuthorizationService = projectAuthorizationService;
        this.repositoryRepository = repositoryRepository;
        this.taskRepository = taskRepository;
        this.planRepository = planRepository;
        this.codeChangeRepository = codeChangeRepository;
        this.codeChunkRepository = codeChunkRepository;
        this.aiServiceClient = aiServiceClient;
        this.diffGeneratorService = diffGeneratorService;
        this.secretScannerService = secretScannerService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionManager != null ? new TransactionTemplate(transactionManager) : null;
    }

    public CodeGenerationServiceImpl(ProjectAuthorizationService projectAuthorizationService,
                                     RepositoryRepository repositoryRepository,
                                     TaskRepository taskRepository,
                                     PlanRepository planRepository,
                                     CodeChangeRepository codeChangeRepository,
                                     CodeChunkRepository codeChunkRepository,
                                     AiServiceClient aiServiceClient,
                                     DiffGeneratorService diffGeneratorService,
                                     SecretScannerService secretScannerService,
                                     ObjectMapper objectMapper) {
        this(projectAuthorizationService, repositoryRepository, taskRepository, planRepository,
             codeChangeRepository, codeChunkRepository, aiServiceClient, diffGeneratorService,
             secretScannerService, objectMapper, null);
    }

    private record GenerationContext(
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID planId,
            String generationToken,
            Task task,
            Plan plan,
            Repository repository,
            Map<String, String> fileContentsMap,
            Map<String, Object> payload
    ) {}

    @Override
    public CodeChangeSetResponseDto generateCode(UUID projectId, UUID repositoryId, UUID taskId, UUID planId, User currentUser) {
        // 1. Prepare payload and assign generation token in Transaction 1
        GenerationContext context;
        if (transactionTemplate != null) {
            context = transactionTemplate.execute(status -> prepareGenerationContext(projectId, repositoryId, taskId, planId, currentUser));
        } else {
            context = prepareGenerationContext(projectId, repositoryId, taskId, planId, currentUser);
        }

        // 2. Perform HTTP call to AI service OUTSIDE of any DB transaction
        Map<String, Object> aiResult = aiServiceClient.generateCode(context.payload());
        String aiStatus = getString(aiResult, "status", "SUCCESS");
        String aiMessage = getString(aiResult, "message", "Code generation completed");

        if ("INSUFFICIENT_CONTEXT".equalsIgnoreCase(aiStatus)) {
            logger.warn("AI service reported insufficient context for task {}", taskId);
            return new CodeChangeSetResponseDto(taskId, planId, "INSUFFICIENT_CONTEXT", aiMessage, Collections.emptyList(), Instant.now());
        }

        // 3. Delete previous draft changes and persist new changes in Transaction 2 (verifying token)
        if (transactionTemplate != null) {
            return transactionTemplate.execute(status -> persistGeneratedChanges(context, aiResult));
        } else {
            return persistGeneratedChanges(context, aiResult);
        }
    }

    private GenerationContext prepareGenerationContext(UUID projectId, UUID repositoryId, UUID taskId, UUID planId, User currentUser) {
        Project project = projectAuthorizationService.getAuthorizedProject(projectId, currentUser);

        Task task = taskRepository.findByIdAndProjectIdAndRepositoryId(taskId, projectId, repositoryId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task not found in specified project and repository"));

        Plan plan = planRepository.findById(planId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "Plan not found"));

        if (!plan.getTask().getId().equals(task.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PLAN_TASK_MISMATCH", "Plan does not belong to the specified task");
        }

        Repository repository = task.getRepository();
        if (repository.getIndexStatus() != RepositoryIndexStatus.INDEXED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REPOSITORY_NOT_INDEXED", "Repository must be indexed before generating code changes");
        }

        String generationToken = UUID.randomUUID().toString();
        task.setGenerationToken(generationToken);
        task.setStatus(TaskStatus.CODING);
        taskRepository.save(task);

        List<Map<String, Object>> proposedChangesPlan = fromJsonListMap(plan.getProposedChanges());
        Map<String, String> fileContentsMap = new HashMap<>();

        for (Map<String, Object> changeItem : proposedChangesPlan) {
            String fpath = getString(changeItem, "file_path", "");
            if (fpath == null || fpath.isBlank()) continue;

            validateFilePathSafety(fpath);

            String content = readFileContent(repository.getId(), fpath);
            if (content != null) {
                fileContentsMap.put(fpath, content);
            }
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("task_title", task.getTitle());
        payload.put("task_description", task.getDescription());
        payload.put("issue_understanding", fromJsonMap(task.getIssueUnderstanding()));
        payload.put("plan_summary", plan.getObjective());
        payload.put("proposed_changes", proposedChangesPlan);
        payload.put("evidence_chunks", fromJsonListMap(plan.getEvidenceReferences()));
        payload.put("file_contents", fileContentsMap);

        return new GenerationContext(projectId, repositoryId, taskId, planId, generationToken, task, plan, repository, fileContentsMap, payload);
    }

    private CodeChangeSetResponseDto persistGeneratedChanges(GenerationContext context, Map<String, Object> aiResult) {
        UUID taskId = context.taskId();

        // Atomic Compare-And-Set: verify generation_token matches in PostgreSQL/H2 with row lock
        int updatedCount = taskRepository.verifyGenerationTokenAtomic(taskId, context.generationToken());
        if (updatedCount == 0) {
            logger.warn("Aborting code change persistence for task {}: generation token mismatch or stale generation", taskId);
            throw new ApiException(HttpStatus.CONFLICT, "STALE_GENERATION", "A newer code generation was initiated for this task");
        }

        Task currentTask = taskRepository.findById(taskId).orElse(context.task());

        String aiMessage = getString(aiResult, "message", "Code generation completed");
        List<Map<String, Object>> rawChanges = getObjectListMap(aiResult, "changes");

        codeChangeRepository.deleteByTaskId(taskId);

        List<CodeChange> savedEntities = new ArrayList<>();
        List<CodeChangeResponseDto> dtoList = new ArrayList<>();

        for (Map<String, Object> rawItem : rawChanges) {
            String filePath = getString(rawItem, "file_path", "");
            String changeType = getString(rawItem, "change_type", "MODIFY").toUpperCase();
            String reason = getString(rawItem, "reason", "Implement plan requirement");
            String origContent = getString(rawItem, "original_content", context.fileContentsMap().getOrDefault(filePath, ""));
            String propContent = getString(rawItem, "proposed_content", "");

            validateFilePathSafety(filePath);

            String originalHash = diffGeneratorService.computeSha256Hash(origContent);
            String proposedHash = diffGeneratorService.computeSha256Hash(propContent);

            String diff = diffGeneratorService.generateUnifiedDiff(filePath, origContent, propContent);

            SecretScannerService.SecretScanResult secretResult = secretScannerService.scanContent(propContent);

            String changeStatus = "GENERATED";
            String failureReason = null;

            if (secretResult.hasSecret()) {
                changeStatus = "VALIDATION_FAILED";
                failureReason = "SECRET_DETECTED: " + secretResult.reason();
                logger.error("Security scan failed for change on '{}': {}", filePath, failureReason);
            } else if (!diffGeneratorService.validateDiffApplication(origContent, propContent, diff)) {
                changeStatus = "VALIDATION_FAILED";
                failureReason = "DIFF_MISMATCH: Generated unified diff failed clean application verification";
                logger.error("Diff validation failed for change on '{}'", filePath);
            }

            CodeChange change = new CodeChange();
            change.setTask(currentTask);
            change.setPlan(context.plan());
            change.setRepository(context.repository());
            change.setFilePath(filePath);
            change.setOperation(changeType);
            change.setOldContent(origContent);
            change.setNewContent(propContent);
            change.setOriginalHash(originalHash);
            change.setProposedHash(proposedHash);
            change.setDiff(diff);
            change.setReason(reason);
            change.setAffectedSymbols(toJson(rawItem.get("affected_symbols")));
            change.setEvidenceReferences(context.plan().getEvidenceReferences());
            change.setConfidenceLevel(getString(rawItem, "confidence", "HIGH"));
            change.setConfidenceScore(BigDecimal.valueOf(getDouble(rawItem, "confidence_score", 0.90)).setScale(2, RoundingMode.HALF_UP));
            change.setStartLine(getInt(rawItem, "start_line", 1));
            change.setEndLine(getInt(rawItem, "end_line", 1));
            change.setStatus(changeStatus);
            change.setValidationFailureReason(failureReason);

            CodeChange saved = codeChangeRepository.save(change);
            savedEntities.add(saved);
            dtoList.add(mapToDto(saved));
        }

        currentTask.setStatus(TaskStatus.WAITING_FOR_PLAN_APPROVAL);
        taskRepository.save(currentTask);

        logger.info("Persisted {} code change(s) for task {} in repository {}", savedEntities.size(), taskId, context.repository().getRepoName());

        return new CodeChangeSetResponseDto(taskId, context.planId(), "GENERATED", aiMessage, dtoList, Instant.now());
    }

    @Override
    @Transactional(readOnly = true)
    public List<CodeChangeResponseDto> getCodeChanges(UUID projectId, UUID repositoryId, UUID taskId, User currentUser) {
        projectAuthorizationService.getAuthorizedProject(projectId, currentUser);

        Task task = taskRepository.findByIdAndProjectIdAndRepositoryId(taskId, projectId, repositoryId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task not found in specified project and repository"));

        List<CodeChange> changes = codeChangeRepository.findByTaskIdOrderByCreatedAtDesc(task.getId());
        List<CodeChangeResponseDto> dtoList = new ArrayList<>();
        for (CodeChange c : changes) {
            dtoList.add(mapToDto(c));
        }
        return dtoList;
    }

    @Override
    @Transactional(readOnly = true)
    public CodeChangeResponseDto getCodeChangeById(UUID projectId, UUID repositoryId, UUID taskId, UUID changeId, User currentUser) {
        projectAuthorizationService.getAuthorizedProject(projectId, currentUser);

        taskRepository.findByIdAndProjectIdAndRepositoryId(taskId, projectId, repositoryId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task not found in specified project and repository"));

        CodeChange change = codeChangeRepository.findByTaskIdAndId(taskId, changeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CODE_CHANGE_NOT_FOUND", "Code change not found for specified task"));

        return mapToDto(change);
    }

    private String readFileContent(UUID repositoryId, String filePath) {
        // 1. Try reading from disk if available
        if (baseStoragePath != null && !baseStoragePath.isBlank()) {
            Path storageDir = Paths.get(baseStoragePath, repositoryId.toString()).toAbsolutePath().normalize();
            Path targetPath = storageDir.resolve(filePath).normalize();

            // Enforce path confinement & disallow symlink traversal outside repository storage
            if (targetPath.startsWith(storageDir)
                    && Files.exists(targetPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && Files.isRegularFile(targetPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                try {
                    return Files.readString(targetPath);
                } catch (IOException e) {
                    logger.warn("Failed reading disk file '{}': {}", targetPath, e.getMessage());
                }
            }
        }

        // 2. Fallback to assembling content from DB code_chunks
        List<CodeChunk> chunks = codeChunkRepository.findByRepositoryIdAndFilePath(repositoryId, filePath);
        if (chunks != null && !chunks.isEmpty()) {
            return chunks.stream().map(CodeChunk::getContent).collect(Collectors.joining("\n"));
        }

        return null;
    }

    private void validateFilePathSafety(String filePath) {
        if (filePath == null || filePath.isBlank()
                || filePath.contains("..")
                || filePath.startsWith("/")
                || filePath.startsWith("\\")
                || filePath.contains(":\\")
                || filePath.matches("^[a-zA-Z]:.*")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PATH_TRAVERSAL_DETECTED", "Unsafe file path detected: " + filePath);
        }
    }

    private CodeChangeResponseDto mapToDto(CodeChange change) {
        return new CodeChangeResponseDto(
                change.getId(),
                change.getTask().getId(),
                change.getPlan() != null ? change.getPlan().getId() : null,
                change.getRepository() != null ? change.getRepository().getId() : null,
                change.getFilePath(),
                change.getOperation(),
                change.getReason(),
                change.getOldContent(),
                change.getNewContent(),
                change.getOriginalHash(),
                change.getProposedHash(),
                change.getDiff(),
                fromJsonList(change.getAffectedSymbols()),
                fromJsonEvidenceReferences(change.getEvidenceReferences()),
                change.getConfidenceLevel(),
                change.getConfidenceScore() != null ? change.getConfidenceScore().doubleValue() : 0.90,
                change.getStartLine(),
                change.getEndLine(),
                change.getStatus(),
                change.getValidationFailureReason(),
                change.getCreatedAt(),
                change.getUpdatedAt()
        );
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }

    private Map<String, Object> fromJsonMap(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private List<Map<String, Object>> fromJsonListMap(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private List<String> fromJsonList(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private List<EvidenceReferenceDto> fromJsonEvidenceReferences(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<EvidenceReferenceDto>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private String getString(Map<String, Object> map, String key, String defaultValue) {
        if (map == null || !map.containsKey(key) || map.get(key) == null) return defaultValue;
        return map.get(key).toString();
    }

    private int getInt(Map<String, Object> map, String key, int defaultValue) {
        if (map == null || !map.containsKey(key) || map.get(key) == null) return defaultValue;
        try {
            return ((Number) map.get(key)).intValue();
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private double getDouble(Map<String, Object> map, String key, double defaultValue) {
        if (map == null || !map.containsKey(key) || map.get(key) == null) return defaultValue;
        try {
            return ((Number) map.get(key)).doubleValue();
        } catch (Exception e) {
            return defaultValue;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getObjectListMap(Map<String, Object> map, String key) {
        if (map == null || !map.containsKey(key) || !(map.get(key) instanceof List)) return Collections.emptyList();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object item : (List<?>) map.get(key)) {
            if (item instanceof Map) {
                list.add((Map<String, Object>) item);
            }
        }
        return list;
    }
}
