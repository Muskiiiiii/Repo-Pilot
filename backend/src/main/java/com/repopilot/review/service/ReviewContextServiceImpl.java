package com.repopilot.review.service;

import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.change.service.DiffGeneratorService;
import com.repopilot.common.exception.ApiException;
import com.repopilot.plan.entity.Plan;
import com.repopilot.plan.repository.PlanRepository;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.repository.service.SecretDetectionService;
import com.repopilot.review.dto.EvidenceRef;
import com.repopilot.review.dto.ReviewContext;
import com.repopilot.review.dto.ReviewContextRequest;
import com.repopilot.review.entity.ReviewMode;
import com.repopilot.security.service.PreLlmSecretScanner;
import com.repopilot.security.service.PreLlmSecretScannerImpl;
import com.repopilot.security.service.SecretScannerService;
import com.repopilot.task.entity.Task;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.user.entity.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ReviewContextServiceImpl implements ReviewContextService {

    public static final int MAX_FILES = 50;
    public static final int MAX_SOURCE_CHARS = 100_000;
    public static final int MAX_DIFF_LINES = 1000;
    public static final int MAX_EVIDENCE_ITEMS = 20;
    public static final int MAX_EVIDENCE_CHARS = 50_000;
    public static final int MAX_TOTAL_CONTEXT_BYTES = 512_000; // 500 KB = 512,000 bytes
    public static final String CONTEXT_VERSION = "v1.0";

    private static final Set<String> REJECTED_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "ico", "exe", "dll", "so", "dylib",
            "jar", "zip", "tar", "gz", "7z", "pdf", "class", "pyc", "o", "obj",
            "env", "pem", "key", "p12", "jks"
    );

    private final ProjectAuthorizationService projectAuthorizationService;
    private final ProjectRepository projectRepository;
    private final RepositoryRepository repositoryRepository;
    private final TaskRepository taskRepository;
    private final CodeChangeRepository codeChangeRepository;
    private final PlanRepository planRepository;
    private final DiffGeneratorService diffGeneratorService;
    private final SecretScannerService secretScannerService;
    private final SecretDetectionService secretDetectionService;
    private final PreLlmSecretScanner preLlmSecretScanner;

    @org.springframework.beans.factory.annotation.Autowired
    public ReviewContextServiceImpl(ProjectAuthorizationService projectAuthorizationService,
                                   ProjectRepository projectRepository,
                                   RepositoryRepository repositoryRepository,
                                   TaskRepository taskRepository,
                                   CodeChangeRepository codeChangeRepository,
                                   PlanRepository planRepository,
                                   DiffGeneratorService diffGeneratorService,
                                   SecretScannerService secretScannerService,
                                   SecretDetectionService secretDetectionService,
                                   PreLlmSecretScanner preLlmSecretScanner) {
        this.projectAuthorizationService = projectAuthorizationService;
        this.projectRepository = projectRepository;
        this.repositoryRepository = repositoryRepository;
        this.taskRepository = taskRepository;
        this.codeChangeRepository = codeChangeRepository;
        this.planRepository = planRepository;
        this.diffGeneratorService = diffGeneratorService;
        this.secretScannerService = secretScannerService;
        this.secretDetectionService = secretDetectionService;
        this.preLlmSecretScanner = preLlmSecretScanner != null ? preLlmSecretScanner : new PreLlmSecretScannerImpl();
    }

    public ReviewContextServiceImpl(ProjectAuthorizationService projectAuthorizationService,
                                   ProjectRepository projectRepository,
                                   RepositoryRepository repositoryRepository,
                                   TaskRepository taskRepository,
                                   CodeChangeRepository codeChangeRepository,
                                   PlanRepository planRepository,
                                   DiffGeneratorService diffGeneratorService,
                                   SecretScannerService secretScannerService,
                                   SecretDetectionService secretDetectionService) {
        this(projectAuthorizationService, projectRepository, repositoryRepository, taskRepository,
             codeChangeRepository, planRepository, diffGeneratorService, secretScannerService, secretDetectionService, new PreLlmSecretScannerImpl());
    }

    @Override
    @Transactional(readOnly = true)
    public ReviewContext buildReviewContext(UUID projectId, UUID repositoryId, UUID taskId, UUID codeChangeId, ReviewMode reviewMode, User requester) {
        if (reviewMode == null) {
            reviewMode = ReviewMode.CODE_CHANGE_REVIEW;
        }

        // 1. Composite Authorization & Hierarchy Validation
        Project project = projectAuthorizationService.getAuthorizedProject(projectId, requester);
        projectAuthorizationService.requireMinimumRole(project, requester, ProjectRole.VIEWER);

        Repository repo = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", "Repository not found"));
        if (!repo.getProject().getId().equals(projectId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Repository does not belong to specified project");
        }

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task not found"));
        if (!task.getRepository().getId().equals(repositoryId) || !task.getProject().getId().equals(projectId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Task does not belong to specified project/repository");
        }

        CodeChange targetCodeChange = null;
        if (codeChangeId != null) {
            targetCodeChange = codeChangeRepository.findById(codeChangeId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CODE_CHANGE_NOT_FOUND", "CodeChange not found"));
            if (!targetCodeChange.getTask().getId().equals(taskId) ||
                (targetCodeChange.getRepository() != null && !targetCodeChange.getRepository().getId().equals(repositoryId))) {
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "CodeChange does not belong to specified task/repository");
            }
        }

        if (reviewMode == ReviewMode.CODE_CHANGE_REVIEW && targetCodeChange == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MISSING_CODE_CHANGE", "codeChangeId is required for CODE_CHANGE_REVIEW");
        }

        // 2. Mode-Specific Construction
        String filePath = null;
        String operation = "MODIFY";
        String rawOriginal = "";
        String rawProposed = "";
        String rawDiff = "";
        String origHash = "";
        String propHash = "";
        List<EvidenceRef> evidenceRefs = new ArrayList<>();

        if (reviewMode == ReviewMode.CODE_CHANGE_REVIEW && targetCodeChange != null) {
            filePath = targetCodeChange.getFilePath();
            validatePath(filePath);

            validateSecurityFileType(filePath, reviewMode);

            operation = targetCodeChange.getOperation() != null ? targetCodeChange.getOperation() : "MODIFY";
            rawOriginal = targetCodeChange.getOldContent() != null ? targetCodeChange.getOldContent() : "";
            rawProposed = targetCodeChange.getNewContent() != null ? targetCodeChange.getNewContent() : "";

            origHash = targetCodeChange.getOriginalHash() != null ? targetCodeChange.getOriginalHash() : diffGeneratorService.computeSha256Hash(rawOriginal);
            propHash = targetCodeChange.getProposedHash() != null ? targetCodeChange.getProposedHash() : diffGeneratorService.computeSha256Hash(rawProposed);

            if (targetCodeChange.getDiff() != null && !targetCodeChange.getDiff().isBlank()) {
                rawDiff = targetCodeChange.getDiff();
            } else {
                rawDiff = diffGeneratorService.generateUnifiedDiff(filePath, rawOriginal, rawProposed);
            }

            // Enforce Phase 13 Context Limits
            byte[] diffBytes = rawDiff.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (diffBytes.length > MAX_TOTAL_CONTEXT_BYTES) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "REJECTED_CONTEXT_TOO_LARGE",
                        "Unified diff size (" + diffBytes.length + " bytes) exceeds maximum limit of " + MAX_TOTAL_CONTEXT_BYTES + " bytes");
            }
            int lineCount = (int) rawDiff.lines().count();
            if (lineCount > 10_000) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "REJECTED_CONTEXT_TOO_LARGE",
                        "Changed lines count (" + lineCount + ") exceeds maximum limit of 10000 lines");
            }

            // Add CodeChange evidence ref
            evidenceRefs.add(new EvidenceRef(
                    "CODE_CHANGE",
                    projectId,
                    repositoryId,
                    taskId,
                    targetCodeChange.getId(),
                    filePath,
                    "PROPOSED",
                    targetCodeChange.getStartLine() != null ? targetCodeChange.getStartLine() : 1,
                    targetCodeChange.getEndLine() != null ? targetCodeChange.getEndLine() : 100,
                    propHash,
                    diffGeneratorService.computeSha256Hash(repo.getRepoName() + ":" + task.getId()),
                    null,
                    "PROJECT_MEMBER"
            ));
        } else if (reviewMode == ReviewMode.TASK_IMPLEMENTATION_REVIEW) {
            filePath = "TASK_SCOPE";
            operation = "TASK_REVIEW";

            // Add task specification evidence
            String taskSpecStr = "Task Title: " + task.getTitle() + "\nDescription: " + (task.getDescription() != null ? task.getDescription() : "");
            taskSpecStr = preLlmSecretScanner.redactSecrets(taskSpecStr);
            evidenceRefs.add(new EvidenceRef(
                    "TASK_SPEC",
                    projectId,
                    repositoryId,
                    taskId,
                    null,
                    "TASK_SPEC",
                    "N/A",
                    1,
                    10,
                    diffGeneratorService.computeSha256Hash(taskSpecStr),
                    diffGeneratorService.computeSha256Hash(task.getId().toString()),
                    null,
                    "PROJECT_MEMBER"
            ));

            List<Plan> plans = planRepository.findByTaskId(taskId);
            if (!plans.isEmpty()) {
                Plan plan = plans.get(0);
                String planObj = plan.getObjective();
                if (planObj != null) {
                    evidenceRefs.add(new EvidenceRef(
                            "PLAN_SPEC",
                            projectId,
                            repositoryId,
                            taskId,
                            null,
                            "PLAN_SPEC",
                            "N/A",
                            1,
                            20,
                            diffGeneratorService.computeSha256Hash(planObj),
                            diffGeneratorService.computeSha256Hash(plan.getId().toString()),
                            null,
                            "PROJECT_MEMBER"
                    ));
                }
            }

            List<CodeChange> changes = codeChangeRepository.findByTaskId(taskId);
            if (changes.size() > MAX_FILES) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "REJECTED_CONTEXT_TOO_LARGE",
                        "File count (" + changes.size() + ") exceeds maximum limit of " + MAX_FILES + " files");
            }
            int count = 0;
            StringBuilder combinedDiffs = new StringBuilder();
            for (CodeChange cc : changes) {
                if (cc.getFilePath() != null) {
                    validatePath(cc.getFilePath());
                }
                String d = cc.getDiff() != null ? cc.getDiff() : diffGeneratorService.generateUnifiedDiff(cc.getFilePath(), cc.getOldContent(), cc.getNewContent());
                combinedDiffs.append("--- ").append(cc.getFilePath()).append("\n").append(d).append("\n");

                evidenceRefs.add(new EvidenceRef(
                        "CODE_CHANGE",
                        projectId,
                        repositoryId,
                        taskId,
                        cc.getId(),
                        cc.getFilePath(),
                        "PROPOSED",
                        cc.getStartLine() != null ? cc.getStartLine() : 1,
                        cc.getEndLine() != null ? cc.getEndLine() : 100,
                        cc.getProposedHash() != null ? cc.getProposedHash() : diffGeneratorService.computeSha256Hash(cc.getNewContent()),
                        diffGeneratorService.computeSha256Hash(repo.getRepoName()),
                        null,
                        "PROJECT_MEMBER"
                ));
            }

            byte[] combinedBytes = combinedDiffs.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (combinedBytes.length > MAX_TOTAL_CONTEXT_BYTES) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "REJECTED_CONTEXT_TOO_LARGE",
                        "Unified diff size (" + combinedBytes.length + " bytes) exceeds maximum limit of " + MAX_TOTAL_CONTEXT_BYTES + " bytes");
            }
            int lineCount = (int) combinedDiffs.toString().lines().count();
            if (lineCount > 10_000) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "REJECTED_CONTEXT_TOO_LARGE",
                        "Changed lines count (" + lineCount + ") exceeds maximum limit of 10000 lines");
            }

            rawDiff = combinedDiffs.toString();
            origHash = diffGeneratorService.computeSha256Hash(task.getId().toString());
            propHash = diffGeneratorService.computeSha256Hash(rawDiff);
        } else if (reviewMode == ReviewMode.SECURITY_REVIEW) {
            if (targetCodeChange != null && targetCodeChange.getFilePath() != null) {
                filePath = targetCodeChange.getFilePath();
                validatePath(filePath);
                validateSecurityFileType(filePath, reviewMode);

                rawOriginal = targetCodeChange.getOldContent() != null ? targetCodeChange.getOldContent() : "";
                rawProposed = targetCodeChange.getNewContent() != null ? targetCodeChange.getNewContent() : "";

                origHash = diffGeneratorService.computeSha256Hash(rawOriginal);
                propHash = diffGeneratorService.computeSha256Hash(rawProposed);
                rawDiff = targetCodeChange.getDiff() != null ? targetCodeChange.getDiff() : diffGeneratorService.generateUnifiedDiff(filePath, rawOriginal, rawProposed);

                byte[] diffBytes = rawDiff.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                if (diffBytes.length > MAX_TOTAL_CONTEXT_BYTES) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "REJECTED_CONTEXT_TOO_LARGE",
                            "Unified diff size (" + diffBytes.length + " bytes) exceeds maximum limit of " + MAX_TOTAL_CONTEXT_BYTES + " bytes");
                }
                int lineCount = (int) rawDiff.lines().count();
                if (lineCount > 10_000) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "REJECTED_CONTEXT_TOO_LARGE",
                            "Changed lines count (" + lineCount + ") exceeds maximum limit of 10000 lines");
                }

                evidenceRefs.add(new EvidenceRef(
                        "CODE_CHANGE",
                        projectId,
                        repositoryId,
                        taskId,
                        targetCodeChange.getId(),
                        filePath,
                        "PROPOSED",
                        1,
                        100,
                        propHash,
                        diffGeneratorService.computeSha256Hash(repo.getRepoName()),
                        null,
                        "PROJECT_MEMBER"
                ));
            } else {
                filePath = "SECURITY_SCOPE";
                operation = "SECURITY_AUDIT";
                origHash = diffGeneratorService.computeSha256Hash(repo.getId().toString());
                propHash = diffGeneratorService.computeSha256Hash("SECURITY_SCOPE");
                rawDiff = "Security Audit Context Scope";
            }
        }

        // 3. Secret Redaction on context text output using PreLlmSecretScanner
        String cleanOriginal = preLlmSecretScanner.redactSecrets(rawOriginal);
        String cleanProposed = preLlmSecretScanner.redactSecrets(rawProposed);
        String cleanDiff = preLlmSecretScanner.redactSecrets(rawDiff);

        cleanOriginal = truncateText(cleanOriginal, 100_000);
        cleanProposed = truncateText(cleanProposed, 100_000);
        cleanDiff = truncateLines(cleanDiff, 1_000);

        // 4. Wrap inside untrusted source code tags
        String fencedOriginal = wrapUntrusted("<UNTRUSTED_SOURCE_CODE side=\"ORIGINAL\" filePath=\"" + filePath + "\">\n" + cleanOriginal + "\n</UNTRUSTED_SOURCE_CODE>");
        String fencedProposed = wrapUntrusted("<UNTRUSTED_SOURCE_CODE side=\"PROPOSED\" filePath=\"" + filePath + "\">\n" + cleanProposed + "\n</UNTRUSTED_SOURCE_CODE>");
        String fencedDiff = wrapUntrusted("<UNTRUSTED_DIFF filePath=\"" + filePath + "\">\n" + cleanDiff + "\n</UNTRUSTED_DIFF>");

        // Limit evidence refs to MAX_EVIDENCE_ITEMS
        if (evidenceRefs.size() > MAX_EVIDENCE_ITEMS) {
            evidenceRefs = evidenceRefs.subList(0, MAX_EVIDENCE_ITEMS);
        }

        // Sort evidence refs deterministically
        List<EvidenceRef> sortedEvidence = evidenceRefs.stream()
                .sorted(Comparator.comparing((EvidenceRef e) -> e.sourceType() != null ? e.sourceType() : "")
                        .thenComparing(e -> e.filePath() != null ? e.filePath() : "")
                        .thenComparing(e -> e.startLine() != null ? e.startLine() : 0)
                        .thenComparing(e -> e.contentHash() != null ? e.contentHash() : ""))
                .collect(Collectors.toList());

        // Construct transient context object to compute reviewContextHash
        ReviewContext tempContext = new ReviewContext(
                projectId,
                repositoryId,
                taskId,
                targetCodeChange != null ? targetCodeChange.getId() : null,
                reviewMode,
                filePath,
                operation,
                fencedOriginal,
                fencedProposed,
                fencedDiff,
                origHash,
                propHash,
                "",
                CONTEXT_VERSION,
                false,
                sortedEvidence
        );

        String contextHash = computeReviewContextHash(tempContext);
        boolean isStale = isContextStale(tempContext);

        return new ReviewContext(
                projectId,
                repositoryId,
                taskId,
                targetCodeChange != null ? targetCodeChange.getId() : null,
                reviewMode,
                filePath,
                operation,
                fencedOriginal,
                fencedProposed,
                fencedDiff,
                origHash,
                propHash,
                contextHash,
                CONTEXT_VERSION,
                isStale,
                sortedEvidence
        );
    }

    @Override
    public ReviewContext buildReviewContext(ReviewContextRequest request, User requester) {
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "ReviewContextRequest cannot be null");
        }
        return buildReviewContext(request.projectId(), request.repositoryId(), request.taskId(), request.codeChangeId(), request.reviewMode(), requester);
    }

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    @Override
    @Transactional(readOnly = true)
    public boolean isContextStale(ReviewContext context) {
        if (context == null) {
            return true;
        }

        if (context.codeChangeId() != null) {
            Optional<CodeChange> changeOpt = codeChangeRepository.findById(context.codeChangeId());
            if (changeOpt.isEmpty()) {
                return true;
            }
            CodeChange currentChange = changeOpt.get();
            try {
                if (entityManager != null) {
                    entityManager.flush();
                    if (entityManager.contains(currentChange)) {
                        entityManager.refresh(currentChange);
                    }
                }
            } catch (Exception ignored) {}

            if (context.originalHash() != null && currentChange.getOriginalHash() != null) {
                if (!context.originalHash().equals(currentChange.getOriginalHash())) {
                    return true;
                }
            }
            if (context.proposedHash() != null && currentChange.getProposedHash() != null) {
                if (!context.proposedHash().equals(currentChange.getProposedHash())) {
                    return true;
                }
            }
            if (context.filePath() != null && currentChange.getFilePath() != null) {
                if (!context.filePath().equals(currentChange.getFilePath())) {
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public String computeReviewContextHash(ReviewContext context) {
        if (context == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONTEXT", "ReviewContext cannot be null");
        }

        String version = context.contextVersion() != null ? context.contextVersion() : CONTEXT_VERSION;
        String pId = context.projectId() != null ? context.projectId().toString() : "NONE";
        String rId = context.repositoryId() != null ? context.repositoryId().toString() : "NONE";
        String tId = context.taskId() != null ? context.taskId().toString() : "NONE";
        String cId = context.codeChangeId() != null ? context.codeChangeId().toString() : "NONE";
        String mode = context.reviewMode() != null ? context.reviewMode().name() : "NONE";
        String file = context.filePath() != null ? context.filePath() : "NONE";
        String op = context.operation() != null ? context.operation() : "NONE";
        String origHash = context.originalHash() != null ? context.originalHash() : "NONE";
        String propHash = context.proposedHash() != null ? context.proposedHash() : "NONE";

        List<String> evidenceHashes = (context.evidenceReferences() == null) ? List.of() :
                context.evidenceReferences().stream()
                        .sorted(Comparator.comparing((EvidenceRef e) -> e.sourceType() != null ? e.sourceType() : "")
                                .thenComparing(e -> e.filePath() != null ? e.filePath() : "")
                                .thenComparing(e -> e.startLine() != null ? e.startLine() : 0)
                                .thenComparing(e -> e.contentHash() != null ? e.contentHash() : ""))
                        .map(e -> (e.sourceType() + ":" + (e.filePath() != null ? e.filePath() : "") + ":" + (e.contentHash() != null ? e.contentHash() : "")))
                        .toList();

        String diffHash = context.unifiedDiff() != null ? diffGeneratorService.computeSha256Hash(context.unifiedDiff()) : "NONE";

        String safeFile = file.replace("\\", "\\\\").replace("|", "\\|");
        String canonical = String.join("|", version, pId, rId, tId, cId, mode, safeFile, op, origHash, propHash, diffHash, String.join(",", evidenceHashes));
        return diffGeneratorService.computeSha256Hash(canonical);
    }

    @Override
    public void validatePath(String path) {
        if (path == null || path.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "File path cannot be null or blank");
        }

        // 1. Control characters & null bytes check BEFORE any string processing
        for (char c : path.toCharArray()) {
            if (c < 32 || c == 127) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "Path contains control characters or null bytes");
            }
        }

        // 2. URL-encoded traversal or escaped dot patterns
        String lower = path.toLowerCase();
        if (lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c") || lower.contains("\\u0000") || lower.contains("%00")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "Path contains encoded traversal characters");
        }

        // 3. Absolute path checks (POSIX / Windows drive letter)
        if (path.startsWith("/") || path.startsWith("\\") || path.matches("^[a-zA-Z]:.*")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "Absolute paths are strictly rejected: " + path);
        }

        // 4. Raw Traversal sequence checks BEFORE normalization
        if (path.contains("..") || path.contains("./..") || path.contains(".\\..")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "Path traversal sequence detected: " + path);
        }

        // 5. Unicode Normalization check
        String nfcNormalized = Normalizer.normalize(path, Normalizer.Form.NFC);
        if (nfcNormalized.contains("..") || nfcNormalized.startsWith("/") || nfcNormalized.startsWith("\\")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "Path normalization security check failed");
        }

        // 6. Path object resolution check without file system access
        Path p = Paths.get(path);
        if (p.isAbsolute()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "Resolved absolute path rejected");
        }
        Path normalized = p.normalize();
        if (normalized.startsWith("..") || normalized.toString().contains("..")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "Normalized path escapes root");
        }
    }

    private void validateSecurityFileType(String filePath, ReviewMode reviewMode) {
        if (filePath == null) return;
        String ext = "";
        int dotIndex = filePath.lastIndexOf('.');
        if (dotIndex >= 0 && dotIndex < filePath.length() - 1) {
            ext = filePath.substring(dotIndex + 1).toLowerCase();
        }

        if (REJECTED_EXTENSIONS.contains(ext) || secretDetectionService.isSecretFilename(filePath)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REJECTED_FILE_TYPE", "File type or secret filename is excluded from review scope: " + filePath);
        }
    }

    private String truncateText(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n[TRUNCATED AT MAX LIMIT: " + maxChars + " CHARACTERS]";
    }

    private String truncateLines(String text, int maxLines) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        if (lines.length <= maxLines) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLines; i++) {
            sb.append(lines[i]).append("\n");
        }
        sb.append("[TRUNCATED AT MAX LIMIT: ").append(maxLines).append(" LINES]");
        return sb.toString();
    }

    private String wrapUntrusted(String text) {
        return text;
    }
}
