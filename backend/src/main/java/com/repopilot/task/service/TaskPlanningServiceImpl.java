package com.repopilot.task.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.ai.client.AiServiceClient;
import com.repopilot.common.exception.ApiException;
import com.repopilot.plan.entity.Plan;
import com.repopilot.plan.repository.PlanRepository;
import com.repopilot.project.entity.Project;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.entity.RepositoryIndexStatus;
import com.repopilot.repository.repository.EmbeddingRepository;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.task.dto.*;
import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskPriority;
import com.repopilot.task.entity.TaskSourceType;
import com.repopilot.task.entity.TaskStatus;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.user.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
public class TaskPlanningServiceImpl implements TaskPlanningService {

    private static final Logger logger = LoggerFactory.getLogger(TaskPlanningServiceImpl.class);

    private final ProjectAuthorizationService projectAuthorizationService;
    private final RepositoryRepository repositoryRepository;
    private final TaskRepository taskRepository;
    private final PlanRepository planRepository;
    private final EmbeddingRepository embeddingRepository;
    private final AiServiceClient aiServiceClient;
    private final ObjectMapper objectMapper;

    public TaskPlanningServiceImpl(ProjectAuthorizationService projectAuthorizationService,
                                  RepositoryRepository repositoryRepository,
                                  TaskRepository taskRepository,
                                  PlanRepository planRepository,
                                  EmbeddingRepository embeddingRepository,
                                  AiServiceClient aiServiceClient,
                                  ObjectMapper objectMapper) {
        this.projectAuthorizationService = projectAuthorizationService;
        this.repositoryRepository = repositoryRepository;
        this.taskRepository = taskRepository;
        this.planRepository = planRepository;
        this.embeddingRepository = embeddingRepository;
        this.aiServiceClient = aiServiceClient;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public TaskResponseDto createTask(UUID projectId, UUID repositoryId, TaskCreateRequestDto request, User currentUser) {
        Project project = projectAuthorizationService.getAuthorizedProject(projectId, currentUser);

        Repository repository = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", "Repository not found"));

        if (!repository.getProject().getId().equals(project.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REPOSITORY_NOT_IN_PROJECT", "Repository does not belong to the project");
        }

        if (request.getTitle() == null || request.getTitle().trim().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TITLE", "Task title is required");
        }
        if (request.getDescription() == null || request.getDescription().trim().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DESCRIPTION", "Task description is required");
        }

        Task task = new Task();
        task.setProject(project);
        task.setRepository(repository);
        task.setCreatedBy(currentUser);
        task.setTitle(request.getTitle().trim());
        task.setDescription(request.getDescription().trim());
        task.setStatus(TaskStatus.CREATED);
        task.setPriority(TaskPriority.MEDIUM);
        task.setSourceType(TaskSourceType.MANUAL);

        Task savedTask = taskRepository.save(task);
        logger.info("Created task '{}' ({}) in repository '{}'", savedTask.getTitle(), savedTask.getId(), repository.getRepoName());

        return mapToTaskResponseDto(savedTask);
    }

    @Override
    @Transactional(readOnly = true)
    public TaskResponseDto getTask(UUID projectId, UUID repositoryId, UUID taskId, User currentUser) {
        projectAuthorizationService.getAuthorizedProject(projectId, currentUser);

        Task task = taskRepository.findByIdAndProjectIdAndRepositoryId(taskId, projectId, repositoryId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task not found in specified project and repository"));

        return mapToTaskResponseDto(task);
    }

    @Override
    @Transactional
    public PlanResponseDto generatePlan(UUID projectId, UUID repositoryId, UUID taskId, User currentUser) {
        Project project = projectAuthorizationService.getAuthorizedProject(projectId, currentUser);

        Task task = taskRepository.findByIdAndProjectIdAndRepositoryId(taskId, projectId, repositoryId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task not found in specified project and repository"));

        Repository repository = task.getRepository();
        if (repository.getIndexStatus() != RepositoryIndexStatus.INDEXED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REPOSITORY_NOT_INDEXED",
                    "Repository is not indexed yet. Please index the repository before generating an implementation plan.");
        }

        // 1. Update status to ANALYZING & run Issue Understanding
        task.setStatus(TaskStatus.ANALYZING);
        taskRepository.save(task);

        Map<String, Object> understandingMap = aiServiceClient.analyzeIssue(task.getTitle(), task.getDescription());
        String understandingJson = toJson(understandingMap);
        task.setIssueUnderstanding(understandingJson);

        // 2. Perform Codebase Evidence Retrieval
        task.setStatus(TaskStatus.PLANNING);
        taskRepository.save(task);

        List<String> searchQueries = extractSearchQueries(understandingMap, task.getTitle());
        List<Map<String, Object>> evidenceChunksPayload = new ArrayList<>();
        List<EvidenceReferenceDto> evidenceReferences = new ArrayList<>();

        Set<UUID> seenChunkIds = new HashSet<>();

        for (String query : searchQueries) {
            if (query == null || query.isBlank()) continue;
            try {
                String queryVector = aiServiceClient.generateQueryEmbedding(query.trim());
                List<Object[]> rawResults = embeddingRepository.searchSemanticChunksNative(repositoryId, queryVector, 5);
                if (rawResults != null) {
                    for (Object[] row : rawResults) {
                        UUID chunkId = row[0] instanceof UUID ? (UUID) row[0] : UUID.fromString(row[0].toString());
                        if (seenChunkIds.contains(chunkId)) continue;
                        seenChunkIds.add(chunkId);

                        int chunkIndex = row[1] instanceof Number ? ((Number) row[1]).intValue() : 0;
                        String content = row[2] != null ? row[2].toString() : "";
                        String symbolName = row[3] != null ? row[3].toString() : "";
                        String chunkType = row[4] != null ? row[4].toString() : "";
                        int startLine = row[5] instanceof Number ? ((Number) row[5]).intValue() : 0;
                        int endLine = row[6] instanceof Number ? ((Number) row[6]).intValue() : 0;
                        String filePath = row[7] != null ? row[7].toString() : "";
                        String fileLanguage = row[8] != null ? row[8].toString() : "";
                        double distance = row[9] instanceof Number ? ((Number) row[9]).doubleValue() : 0.0;

                        double rawSimilarity = Math.max(0.0, Math.min(1.0, 1.0 - distance));
                        double similarityScore = BigDecimal.valueOf(rawSimilarity).setScale(4, RoundingMode.HALF_UP).doubleValue();
                        double roundedDistance = BigDecimal.valueOf(distance).setScale(4, RoundingMode.HALF_UP).doubleValue();

                        Map<String, Object> chunkMap = new HashMap<>();
                        chunkMap.put("chunk_id", chunkId.toString());
                        chunkMap.put("file_path", filePath);
                        chunkMap.put("file_language", fileLanguage);
                        chunkMap.put("symbol_name", symbolName);
                        chunkMap.put("chunk_type", chunkType);
                        chunkMap.put("start_line", startLine);
                        chunkMap.put("end_line", endLine);
                        chunkMap.put("content", content);
                        chunkMap.put("similarity_score", similarityScore);
                        chunkMap.put("distance", roundedDistance);
                        evidenceChunksPayload.add(chunkMap);

                        evidenceReferences.add(new EvidenceReferenceDto(
                                chunkId, filePath, fileLanguage, symbolName, chunkType,
                                startLine, endLine, content, similarityScore, roundedDistance,
                                "Retrieved as top semantic match for search query: " + query
                        ));
                    }
                }
            } catch (Exception e) {
                logger.warn("Query vector search failed for query '{}': {}", query, e.getMessage());
            }
        }

        // 3. Generate Implementation Plan from AI Service
        Map<String, Object> planRequestPayload = new HashMap<>();
        planRequestPayload.put("title", task.getTitle());
        planRequestPayload.put("description", task.getDescription());
        planRequestPayload.put("issue_understanding", understandingMap);
        planRequestPayload.put("evidence_chunks", evidenceChunksPayload);

        Map<String, Object> planResultMap = aiServiceClient.generatePlan(planRequestPayload);

        // 4. Save or Update Plan in Database
        List<Plan> existingPlans = planRepository.findByTaskId(taskId);
        Plan plan = existingPlans.isEmpty() ? new Plan() : existingPlans.get(0);

        plan.setTask(task);
        plan.setVersion(existingPlans.isEmpty() ? 1 : plan.getVersion() + 1);
        plan.setObjective(getString(planResultMap, "summary", task.getTitle()));
        plan.setProblemAnalysis(getString(planResultMap, "problem_analysis", ""));
        plan.setAffectedFiles(toJson(getList(planResultMap, "relevant_files")));
        plan.setRelevantSymbols(toJson(getList(planResultMap, "relevant_symbols")));
        plan.setCurrentBehavior(getString(planResultMap, "current_behavior", ""));
        plan.setProposedChanges(toJson(getObjectList(planResultMap, "proposed_changes")));
        plan.setSteps(toJson(getList(planResultMap, "step_by_step_plan")));
        plan.setDataApiChanges(getString(planResultMap, "data_api_changes", ""));
        plan.setTestingStrategy(toJson(getList(planResultMap, "testing_strategy")));
        plan.setRisks(toJson(getList(planResultMap, "risks")));
        plan.setAssumptions(toJson(getList(planResultMap, "assumptions")));
        plan.setOpenQuestions(toJson(getList(planResultMap, "open_questions")));
        plan.setConfidenceLevel(getString(planResultMap, "confidence_level", "MEDIUM"));

        double confScore = getDouble(planResultMap, "confidence_score", 0.70);
        plan.setConfidenceScore(BigDecimal.valueOf(confScore).setScale(2, RoundingMode.HALF_UP));

        boolean isAmbiguous = getBoolean(planResultMap, "is_ambiguous", false);
        plan.setIsAmbiguous(isAmbiguous);
        plan.setAmbiguityReason(getString(planResultMap, "ambiguity_reason", null));
        plan.setEvidenceReferences(toJson(evidenceReferences));
        plan.setStatus("GENERATED");

        Plan savedPlan = planRepository.save(plan);

        // 5. Update Task Status
        task.setStatus(isAmbiguous ? TaskStatus.ANALYZING : TaskStatus.WAITING_FOR_PLAN_APPROVAL);
        taskRepository.save(task);

        logger.info("Generated plan {} for task {} (Confidence: {}, Ambiguous: {})",
                savedPlan.getId(), task.getId(), savedPlan.getConfidenceLevel(), isAmbiguous);

        return mapToPlanResponseDto(savedPlan);
    }

    @Override
    @Transactional(readOnly = true)
    public PlanResponseDto getPlan(UUID projectId, UUID repositoryId, UUID taskId, User currentUser) {
        projectAuthorizationService.getAuthorizedProject(projectId, currentUser);

        taskRepository.findByIdAndProjectIdAndRepositoryId(taskId, projectId, repositoryId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task not found in specified project and repository"));

        List<Plan> plans = planRepository.findByTaskId(taskId);
        if (plans.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "Implementation plan has not been generated for this task yet.");
        }

        return mapToPlanResponseDto(plans.get(0));
    }

    private TaskResponseDto mapToTaskResponseDto(Task task) {
        return new TaskResponseDto(
                task.getId(),
                task.getProject().getId(),
                task.getRepository() != null ? task.getRepository().getId() : null,
                task.getTitle(),
                task.getDescription(),
                task.getStatus().name(),
                task.getPriority() != null ? task.getPriority().name() : "MEDIUM",
                task.getIssueUnderstanding(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }

    private PlanResponseDto mapToPlanResponseDto(Plan plan) {
        PlanResponseDto dto = new PlanResponseDto();
        dto.setId(plan.getId());
        dto.setTaskId(plan.getTask().getId());
        dto.setVersion(plan.getVersion());
        dto.setSummary(plan.getObjective());
        dto.setProblemAnalysis(plan.getProblemAnalysis());
        dto.setRelevantFiles(fromJsonList(plan.getAffectedFiles()));
        dto.setRelevantSymbols(fromJsonList(plan.getRelevantSymbols()));
        dto.setCurrentBehavior(plan.getCurrentBehavior());
        dto.setProposedChanges(fromJsonProposedChanges(plan.getProposedChanges()));
        dto.setStepByStepPlan(fromJsonList(plan.getSteps()));
        dto.setDataApiChanges(plan.getDataApiChanges());
        dto.setTestingStrategy(fromJsonList(plan.getTestingStrategy()));
        dto.setRisks(fromJsonList(plan.getRisks()));
        dto.setAssumptions(fromJsonList(plan.getAssumptions()));
        dto.setOpenQuestions(fromJsonList(plan.getOpenQuestions()));
        dto.setConfidenceLevel(plan.getConfidenceLevel());
        dto.setConfidenceScore(plan.getConfidenceScore() != null ? plan.getConfidenceScore().doubleValue() : 0.70);
        dto.setIsAmbiguous(plan.getIsAmbiguous());
        dto.setAmbiguityReason(plan.getAmbiguityReason());
        dto.setEvidenceReferences(fromJsonEvidenceReferences(plan.getEvidenceReferences()));
        dto.setStatus(plan.getStatus());
        dto.setCreatedAt(plan.getCreatedAt());
        return dto;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractSearchQueries(Map<String, Object> understandingMap, String fallbackTitle) {
        List<String> queries = new ArrayList<>();
        if (understandingMap != null && understandingMap.containsKey("search_queries")) {
            Object raw = understandingMap.get("search_queries");
            if (raw instanceof List) {
                for (Object q : (List<?>) raw) {
                    if (q != null) queries.add(q.toString());
                }
            }
        }
        if (queries.isEmpty()) {
            queries.add(fallbackTitle);
        }
        return queries;
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            logger.warn("Failed to serialize object to JSON: {}", e.getMessage());
            return "[]";
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

    private List<ProposedChangeDto> fromJsonProposedChanges(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<ProposedChangeDto>>() {});
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

    @SuppressWarnings("unchecked")
    private List<String> getList(Map<String, Object> map, String key) {
        if (map == null || !map.containsKey(key) || !(map.get(key) instanceof List)) return Collections.emptyList();
        List<String> list = new ArrayList<>();
        for (Object item : (List<?>) map.get(key)) {
            if (item != null) list.add(item.toString());
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private List<Object> getObjectList(Map<String, Object> map, String key) {
        if (map == null || !map.containsKey(key) || !(map.get(key) instanceof List)) return Collections.emptyList();
        return (List<Object>) map.get(key);
    }

    private double getDouble(Map<String, Object> map, String key, double defaultValue) {
        if (map == null || !map.containsKey(key) || map.get(key) == null) return defaultValue;
        try {
            return ((Number) map.get(key)).doubleValue();
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        if (map == null || !map.containsKey(key) || map.get(key) == null) return defaultValue;
        try {
            return (Boolean) map.get(key);
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
