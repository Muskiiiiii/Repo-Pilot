package com.repopilot.approval.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.approval.dto.HumanApprovalCreateRequest;
import com.repopilot.approval.dto.HumanApprovalResponseDto;
import com.repopilot.approval.entity.ApprovalStatus;
import com.repopilot.approval.entity.HumanApproval;
import com.repopilot.approval.repository.HumanApprovalRepository;
import com.repopilot.audit.service.AuditLogService;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.change.service.DiffGeneratorService;
import com.repopilot.common.exception.ApiException;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.entity.RepositoryBranch;
import com.repopilot.repository.repository.RepositoryBranchRepository;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.review.dto.ReviewContext;
import com.repopilot.review.entity.CodeReview;
import com.repopilot.review.entity.ReviewStatus;
import com.repopilot.review.repository.CodeReviewRepository;
import com.repopilot.review.service.ReviewContextService;
import com.repopilot.task.entity.Task;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.user.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@Service
public class HumanApprovalServiceImpl implements HumanApprovalService {

    private static final Logger logger = LoggerFactory.getLogger(HumanApprovalServiceImpl.class);
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[a-fA-F0-9]{64}$");
    private static final Pattern COMMIT_SHA_PATTERN = Pattern.compile("^[a-fA-F0-9]{40}$");

    private static volatile Consumer<CodeChange> preSaveHook;

    public static void setPreSaveHook(Consumer<CodeChange> hook) {
        preSaveHook = hook;
    }

    private final ProjectAuthorizationService projectAuthorizationService;
    private final RepositoryRepository repositoryRepository;
    private final RepositoryBranchRepository repositoryBranchRepository;
    private final TaskRepository taskRepository;
    private final CodeChangeRepository codeChangeRepository;
    private final CodeReviewRepository codeReviewRepository;
    private final HumanApprovalRepository humanApprovalRepository;
    private final ReviewContextService reviewContextService;
    private final DiffGeneratorService diffGeneratorService;
    private final AuditLogService auditLogService;
    private final PublicationAuditService publicationAuditService;
    private final com.repopilot.review.service.CodeReviewApprovalGateService codeReviewApprovalGateService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HumanApprovalServiceImpl(
            ProjectAuthorizationService projectAuthorizationService,
            RepositoryRepository repositoryRepository,
            RepositoryBranchRepository repositoryBranchRepository,
            TaskRepository taskRepository,
            CodeChangeRepository codeChangeRepository,
            CodeReviewRepository codeReviewRepository,
            HumanApprovalRepository humanApprovalRepository,
            ReviewContextService reviewContextService,
            DiffGeneratorService diffGeneratorService,
            AuditLogService auditLogService
    ) {
        this(projectAuthorizationService, repositoryRepository, repositoryBranchRepository, taskRepository, codeChangeRepository, codeReviewRepository, humanApprovalRepository, reviewContextService, diffGeneratorService, auditLogService, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public HumanApprovalServiceImpl(
            ProjectAuthorizationService projectAuthorizationService,
            RepositoryRepository repositoryRepository,
            RepositoryBranchRepository repositoryBranchRepository,
            TaskRepository taskRepository,
            CodeChangeRepository codeChangeRepository,
            CodeReviewRepository codeReviewRepository,
            HumanApprovalRepository humanApprovalRepository,
            ReviewContextService reviewContextService,
            DiffGeneratorService diffGeneratorService,
            AuditLogService auditLogService,
            @org.springframework.beans.factory.annotation.Autowired(required = false) PublicationAuditService publicationAuditService,
            @org.springframework.beans.factory.annotation.Autowired(required = false) com.repopilot.review.service.CodeReviewApprovalGateService codeReviewApprovalGateService
    ) {
        this.projectAuthorizationService = projectAuthorizationService;
        this.repositoryRepository = repositoryRepository;
        this.repositoryBranchRepository = repositoryBranchRepository;
        this.taskRepository = taskRepository;
        this.codeChangeRepository = codeChangeRepository;
        this.codeReviewRepository = codeReviewRepository;
        this.humanApprovalRepository = humanApprovalRepository;
        this.reviewContextService = reviewContextService;
        this.diffGeneratorService = diffGeneratorService;
        this.auditLogService = auditLogService;
        this.publicationAuditService = publicationAuditService;
        this.codeReviewApprovalGateService = codeReviewApprovalGateService;
    }

    @Override
    @Transactional
    public HumanApprovalResponseDto createApproval(
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            UUID reviewId,
            HumanApprovalCreateRequest request,
            User requester
    ) {
        if (requester == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authenticated user required for human approval");
        }

        if (request != null && !request.isConfirmAction()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ACTION_NOT_CONFIRMED", "Human approval requires explicit confirmAction=true");
        }

        // 1. Resource Validation & Scoping
        Project project = projectAuthorizationService.getAuthorizedProject(projectId, requester);
        projectAuthorizationService.requireMinimumRole(project, requester, ProjectRole.MEMBER);
        ProjectRole userRole = projectAuthorizationService.getUserRoleInProject(project, requester);

        Repository repo = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", "Repository not found"));

        if (repo.getProject() == null || repo.getProject().getId() == null || !repo.getProject().getId().equals(projectId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "REPOSITORY_PROJECT_MISMATCH", "Repository does not belong to the specified Project");
        }

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task not found"));

        if (task.getProject() == null || task.getProject().getId() == null || !task.getProject().getId().equals(projectId) ||
            task.getRepository() == null || task.getRepository().getId() == null || !task.getRepository().getId().equals(repositoryId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "TASK_REPOSITORY_MISMATCH", "Task does not belong to specified Project and Repository");
        }

        CodeChange codeChange = codeChangeRepository.findById(codeChangeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CODE_CHANGE_NOT_FOUND", "CodeChange not found"));

        if (codeChange.getTask() == null || codeChange.getTask().getId() == null || !codeChange.getTask().getId().equals(taskId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CODE_CHANGE_TASK_MISMATCH", "CodeChange does not belong to specified Task");
        }

        // 2. Precondition Check: Revocation / Superseded Status Isolation
        Optional<HumanApproval> existingActive = humanApprovalRepository.findByCodeChangeId(codeChangeId)
                .stream()
                .filter(a -> a.getStatus() == ApprovalStatus.APPROVED)
                .findFirst();

        if (existingActive.isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "ACTIVE_APPROVAL_EXISTS", "CodeChange already has an active APPROVED snapshot");
        }

        // 3. Review Identification & Code Review Eligibility Compliance Gate (Phase 13 Slice 7)
        if (codeReviewApprovalGateService != null) {
            com.repopilot.review.dto.CodeReviewEligibilityResultDto eligibility = codeReviewApprovalGateService.evaluateReviewEligibilityForApproval(
                    projectId, repositoryId, taskId, codeChangeId, reviewId, requester
            );
            if (!eligibility.eligible()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INELIGIBLE_CODE_REVIEW", eligibility.reason());
            }
        }

        CodeReview review;
        if (reviewId != null) {
            review = codeReviewRepository.findById(reviewId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "CodeReview not found"));
        } else {
            List<CodeReview> reviews = codeReviewRepository.findByCodeChangeId(codeChangeId);
            review = reviews.stream()
                    .filter(r -> r.getStatus() == ReviewStatus.COMPLETED)
                    .max(Comparator.comparing(CodeReview::getCreatedAt))
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "NO_COMPLETED_REVIEW", "No completed CodeReview found for this CodeChange"));
        }

        if (review.getCodeChange() == null || review.getCodeChange().getId() == null || !review.getCodeChange().getId().equals(codeChangeId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "REVIEW_CODE_CHANGE_MISMATCH", "CodeReview does not belong to specified CodeChange");
        }

        if (review.getStatus() != ReviewStatus.COMPLETED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REVIEW_NOT_COMPLETED", "CodeReview must be in COMPLETED status to be approved");
        }

        // 4. CodeChange Integrity & Hashes
        String originalHash = codeChange.getOriginalHash();
        if (originalHash == null || originalHash.isBlank()) {
            originalHash = diffGeneratorService.computeSha256Hash(codeChange.getOldContent() != null ? codeChange.getOldContent() : "");
        }
        String proposedHash = codeChange.getProposedHash();
        if (proposedHash == null || proposedHash.isBlank()) {
            proposedHash = diffGeneratorService.computeSha256Hash(codeChange.getNewContent() != null ? codeChange.getNewContent() : "");
        }
        String diffHash = diffGeneratorService.computeSha256Hash(codeChange.getDiff() != null ? codeChange.getDiff() : "");
        if (diffHash == null || diffHash.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DIFF", "CodeChange has no valid diff to approve");
        }

        // 5. ReviewContext Integrity & Stale Detection
        ReviewContext currentContext = reviewContextService.buildReviewContext(projectId, repositoryId, taskId, codeChangeId, review.getReviewMode(), requester);
        if (currentContext == null || currentContext.isStale() || !review.getReviewContextHash().equals(currentContext.reviewContextHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "STALE_CONTEXT", "CodeChange or ReviewContext has changed since the review was completed");
        }

        // 6. Repository Base SHA Resolution
        String baseSha = resolveAuthoritativeBaseSha(repo, request != null ? request.getRepositoryBaseSha() : null);

        // 7. Approved Files Snapshot
        String filePath = sanitizePath(codeChange.getFilePath());
        List<String> fileList = List.of(filePath);
        String approvedFilesJson;
        try {
            approvedFilesJson = objectMapper.writeValueAsString(fileList);
        } catch (JsonProcessingException e) {
            approvedFilesJson = "[\"" + filePath + "\"]";
        }

        // 8. Approval Version & Idempotency Key
        List<HumanApproval> existingApprovals = humanApprovalRepository.findByCodeChangeId(codeChangeId);
        int approvalVersion = existingApprovals.stream()
                .mapToInt(HumanApproval::getApprovalVersion)
                .max()
                .orElse(0) + 1;

        String idempotencyKey = resolveIdempotencyKey(request != null ? request.getIdempotencyKey() : null, codeChangeId, approvalVersion);
        Optional<HumanApproval> idempotencyCheck = humanApprovalRepository.findByIdempotencyKey(idempotencyKey);
        if (idempotencyCheck.isPresent()) {
            HumanApproval existing = idempotencyCheck.get();
            if (!existing.getCodeChange().getId().equals(codeChangeId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REUSE", "Idempotency key already used for a different CodeChange");
            }
            if (!ApprovalCanonicalEncoder.verifyApprovalRecordHash(existing)) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "APPROVAL_INTEGRITY_FAILURE", "Existing approval record failed hash verification");
            }
            return mapToDto(existing);
        }

        // 9. Snapshot Construction
        HumanApproval approval = new HumanApproval();
        approval.setProject(project);
        approval.setRepository(repo);
        approval.setTask(task);
        approval.setCodeChange(codeChange);
        approval.setReview(review);
        approval.setApprovalVersion(approvalVersion);
        approval.setStatus(ApprovalStatus.APPROVED);
        approval.setRepositoryBaseSha(baseSha.toLowerCase(Locale.ROOT));
        approval.setCodeChangeOriginalHash(originalHash.toLowerCase(Locale.ROOT));
        approval.setCodeChangeProposedHash(proposedHash.toLowerCase(Locale.ROOT));
        approval.setCodeChangeDiffHash(diffHash.toLowerCase(Locale.ROOT));
        approval.setReviewContextHash(review.getReviewContextHash().toLowerCase(Locale.ROOT));
        approval.setApprovedFiles(approvedFilesJson);
        approval.setApprovedChangeCount(1);
        approval.setApprovedByUser(requester);
        approval.setApprovedByUserEmail(requester.getEmail() != null ? requester.getEmail() : "unknown@repopilot.internal");
        approval.setApprovedByUserRole(userRole != null ? userRole.name() : ProjectRole.MEMBER.name());
        approval.setApprovedAt(Instant.now());
        approval.setIdempotencyKey(idempotencyKey);

        // 10. Canonical Hash Computation
        String approvalRecordHash = ApprovalCanonicalEncoder.computeApprovalRecordHash(approval);
        approval.setApprovalRecordHash(approvalRecordHash);

        // Pre-save hook for testing deterministic TOCTOU race condition
        Consumer<CodeChange> hook = preSaveHook;
        if (hook != null) {
            hook.accept(codeChange);
        }

        // 11. Persistence, CodeChange Optimistic Lock Touch & Self-Verification
        codeChange.setStatus("APPROVED");
        codeChange.setApprovedAt(approval.getApprovedAt());
        codeChange.setApprovedBy(requester);
        codeChangeRepository.save(codeChange);

        HumanApproval savedApproval = humanApprovalRepository.saveAndFlush(approval);
        if (!ApprovalCanonicalEncoder.verifyApprovalRecordHash(savedApproval)) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "APPROVAL_INTEGRITY_FAILURE", "Persisted HumanApproval record failed hash self-verification");
        }

        // 12. Audit Log & F-G Publication Audit Event
        try {
            Map<String, Object> auditMeta = new HashMap<>();
            auditMeta.put("approvalId", savedApproval.getId().toString());
            auditMeta.put("approvalVersion", approvalVersion);
            auditMeta.put("repositoryBaseSha", savedApproval.getRepositoryBaseSha());
            auditMeta.put("codeChangeDiffHash", savedApproval.getCodeChangeDiffHash());
            auditMeta.put("approvalRecordHash", savedApproval.getApprovalRecordHash());
            auditMeta.put("approvedByUserRole", savedApproval.getApprovedByUserRole());
            auditLogService.logAction(
                    requester,
                    project,
                    task,
                    "HUMAN_APPROVAL_GRANTED",
                    "HUMAN_APPROVAL",
                    savedApproval.getId(),
                    objectMapper.writeValueAsString(auditMeta)
            );
        } catch (Exception e) {
            logger.warn("Failed to write audit log for approval id={}: {}", savedApproval.getId(), e.getMessage());
        }

        if (publicationAuditService != null) {
            try {
                publicationAuditService.recordEvent(
                        project, repo, task, codeChange, savedApproval, null, requester,
                        com.repopilot.approval.entity.AuditEventType.APPROVAL_CREATED,
                        "UNAPPROVED", "APPROVED", "SUCCESS", "APPROVAL_GRANTED",
                        "Human approval record created and cryptographically bound", null
                );
            } catch (Exception e) {
                logger.warn("Failed to record publication audit event for approval id={}: {}", savedApproval.getId(), e.getMessage());
            }
        }

        return mapToDto(savedApproval);
    }

    @Override
    @Transactional(readOnly = true)
    public HumanApprovalResponseDto getApprovalById(UUID projectId, UUID approvalId, User requester) {
        Project project = projectAuthorizationService.getAuthorizedProject(projectId, requester);
        projectAuthorizationService.requireMinimumRole(project, requester, ProjectRole.VIEWER);

        HumanApproval approval = humanApprovalRepository.findById(approvalId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "APPROVAL_NOT_FOUND", "Approval record not found"));

        if (!approval.getProject().getId().equals(projectId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Approval record does not belong to specified project");
        }

        if (!ApprovalCanonicalEncoder.verifyApprovalRecordHash(approval)) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "APPROVAL_INTEGRITY_FAILURE", "Approval record failed hash self-verification");
        }

        return mapToDto(approval);
    }

    @Override
    @Transactional(readOnly = true)
    public List<HumanApprovalResponseDto> getApprovalsForCodeChange(UUID projectId, UUID codeChangeId, User requester) {
        Project project = projectAuthorizationService.getAuthorizedProject(projectId, requester);
        projectAuthorizationService.requireMinimumRole(project, requester, ProjectRole.VIEWER);

        List<HumanApproval> approvals = humanApprovalRepository.findByCodeChangeId(codeChangeId);
        return approvals.stream()
                .filter(a -> a.getProject().getId().equals(projectId))
                .filter(ApprovalCanonicalEncoder::verifyApprovalRecordHash)
                .map(this::mapToDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<HumanApprovalResponseDto> getApprovalsForTask(UUID projectId, UUID taskId, User requester) {
        Project project = projectAuthorizationService.getAuthorizedProject(projectId, requester);
        projectAuthorizationService.requireMinimumRole(project, requester, ProjectRole.VIEWER);

        List<HumanApproval> approvals = humanApprovalRepository.findByTaskId(taskId);
        return approvals.stream()
                .filter(a -> a.getProject().getId().equals(projectId))
                .filter(ApprovalCanonicalEncoder::verifyApprovalRecordHash)
                .map(this::mapToDto)
                .toList();
    }

    @Override
    @Transactional
    public HumanApprovalResponseDto revokeApproval(UUID projectId, UUID approvalId, String reason, User requester) {
        Project project = projectAuthorizationService.getAuthorizedProject(projectId, requester);
        projectAuthorizationService.requireMinimumRole(project, requester, ProjectRole.MEMBER);

        HumanApproval approval = humanApprovalRepository.findById(approvalId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "APPROVAL_NOT_FOUND", "Approval record not found"));

        if (!approval.getProject().getId().equals(projectId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Approval record does not belong to specified project");
        }

        if (!ApprovalCanonicalEncoder.verifyApprovalRecordHash(approval)) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "APPROVAL_INTEGRITY_FAILURE", "Approval record failed hash self-verification before revocation");
        }

        approval.setStatus(ApprovalStatus.REVOKED);
        approval.setStaleReason(reason != null ? reason : "Revoked by user");
        HumanApproval saved = humanApprovalRepository.saveAndFlush(approval);

        try {
            auditLogService.logAction(
                    requester,
                    project,
                    approval.getTask(),
                    "HUMAN_APPROVAL_REVOKED",
                    "HUMAN_APPROVAL",
                    saved.getId(),
                    "{\"reason\":\"" + (reason != null ? reason.replace("\"", "\\\"") : "Revoked by user") + "\"}"
            );
        } catch (Exception e) {
            logger.warn("Failed to write audit log for approval revocation id={}: {}", saved.getId(), e.getMessage());
        }

        return mapToDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean verifyApprovalIntegrity(UUID approvalId) {
        Optional<HumanApproval> opt = humanApprovalRepository.findById(approvalId);
        return opt.isPresent() && ApprovalCanonicalEncoder.verifyApprovalRecordHash(opt.get());
    }

    private String resolveAuthoritativeBaseSha(Repository repo, String requestedSha) {
        Optional<RepositoryBranch> defaultBranchOpt = repositoryBranchRepository.findByRepositoryIdAndName(repo.getId(), repo.getDefaultBranch());

        if (defaultBranchOpt.isPresent() && defaultBranchOpt.get().getCommitSha() != null) {
            String dbSha = defaultBranchOpt.get().getCommitSha().trim().toLowerCase(Locale.ROOT);
            if (COMMIT_SHA_PATTERN.matcher(dbSha).matches()) {
                if (requestedSha != null && !requestedSha.isBlank() && !requestedSha.trim().equalsIgnoreCase(dbSha)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "BASE_SHA_MISMATCH", "Requested base SHA does not match current repository default branch SHA");
                }
                return dbSha;
            }
        }

        if (requestedSha != null && !requestedSha.isBlank()) {
            String cleanRequested = requestedSha.trim().toLowerCase(Locale.ROOT);
            if (COMMIT_SHA_PATTERN.matcher(cleanRequested).matches()) {
                return cleanRequested;
            }
        }

        throw new ApiException(HttpStatus.BAD_REQUEST, "MISSING_BASE_SHA", "Repository has no valid commit SHA recorded for default branch");
    }

    private String sanitizePath(String path) {
        if (path == null || path.isBlank()) {
            return "file";
        }
        String clean = path.replace("\\", "/").replaceAll("^/+", "");
        if (clean.contains("../") || clean.contains("./") || clean.contains("\0")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILE_PATH", "Path contains dangerous traversal or control characters: " + path);
        }
        return clean;
    }

    private String resolveIdempotencyKey(String userKey, UUID codeChangeId, int version) {
        if (userKey != null && !userKey.isBlank()) {
            return userKey.trim();
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(("APPROVAL_CREATE:" + codeChangeId + ":" + version).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "APPROVAL_CREATE:" + codeChangeId + ":" + version;
        }
    }

    private HumanApprovalResponseDto mapToDto(HumanApproval a) {
        List<String> approvedFilesList = List.of();
        if (a.getApprovedFiles() != null && !a.getApprovedFiles().isBlank()) {
            try {
                approvedFilesList = objectMapper.readValue(a.getApprovedFiles(), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            } catch (Exception e) {
                approvedFilesList = List.of(a.getApprovedFiles());
            }
        }

        return new HumanApprovalResponseDto(
                a.getId(),
                a.getProject().getId(),
                a.getRepository().getId(),
                a.getTask().getId(),
                a.getCodeChange().getId(),
                a.getReview().getId(),
                a.getApprovalVersion(),
                a.getStatus(),
                a.getRepositoryBaseSha(),
                a.getCodeChangeOriginalHash(),
                a.getCodeChangeProposedHash(),
                a.getCodeChangeDiffHash(),
                a.getReviewContextHash(),
                approvedFilesList,
                a.getApprovedChangeCount(),
                a.getApprovalRecordHash(),
                a.getApprovedByUser().getId(),
                a.getApprovedByUserEmail(),
                a.getApprovedByUserRole(),
                a.getApprovedAt(),
                a.getStaleReason(),
                a.getIdempotencyKey(),
                a.getCreatedAt()
        );
    }
}
