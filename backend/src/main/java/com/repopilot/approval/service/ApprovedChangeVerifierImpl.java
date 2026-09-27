package com.repopilot.approval.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.approval.dto.ApprovedChangeVerificationResult;
import com.repopilot.approval.dto.VerificationFailureCategory;
import com.repopilot.approval.entity.ApprovalStatus;
import com.repopilot.approval.entity.HumanApproval;
import com.repopilot.approval.repository.HumanApprovalRepository;
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
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.user.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class ApprovedChangeVerifierImpl implements ApprovedChangeVerifier {

    private static final Logger logger = LoggerFactory.getLogger(ApprovedChangeVerifierImpl.class);
    private static final Pattern COMMIT_SHA_PATTERN = Pattern.compile("^[a-fA-F0-9]{40}$");

    private final ProjectAuthorizationService projectAuthorizationService;
    private final RepositoryRepository repositoryRepository;
    private final RepositoryBranchRepository repositoryBranchRepository;
    private final TaskRepository taskRepository;
    private final CodeChangeRepository codeChangeRepository;
    private final CodeReviewRepository codeReviewRepository;
    private final HumanApprovalRepository humanApprovalRepository;
    private final ReviewContextService reviewContextService;
    private final DiffGeneratorService diffGeneratorService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ApprovedChangeVerifierImpl(
            ProjectAuthorizationService projectAuthorizationService,
            RepositoryRepository repositoryRepository,
            RepositoryBranchRepository repositoryBranchRepository,
            TaskRepository taskRepository,
            CodeChangeRepository codeChangeRepository,
            CodeReviewRepository codeReviewRepository,
            HumanApprovalRepository humanApprovalRepository,
            ReviewContextService reviewContextService,
            DiffGeneratorService diffGeneratorService
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
    }

    @Override
    @Transactional(readOnly = true)
    public ApprovedChangeVerificationResult verifyApprovedChange(
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            UUID approvalId,
            User requester
    ) {
        // 1. Authenticated User Check
        if (requester == null) {
            return ApprovedChangeVerificationResult.failure(
                    VerificationFailureCategory.UNAUTHORIZED,
                    "Authenticated requester required",
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        }

        // 2. Project Role Authorization Check (MEMBER, ADMIN, OWNER allowed; VIEWER rejected)
        Project project;
        try {
            project = projectAuthorizationService.getAuthorizedProject(projectId, requester);
            projectAuthorizationService.requireMinimumRole(project, requester, ProjectRole.MEMBER);
        } catch (ApiException e) {
            return ApprovedChangeVerificationResult.failure(
                    VerificationFailureCategory.UNAUTHORIZED,
                    "Insufficient permissions for project approval verification: " + e.getMessage(),
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        }

        // 3. HumanApproval Existence
        Optional<HumanApproval> approvalOpt = humanApprovalRepository.findById(approvalId);
        if (approvalOpt.isEmpty()) {
            return ApprovedChangeVerificationResult.failure(
                    VerificationFailureCategory.APPROVAL_NOT_FOUND,
                    "Approval record not found: " + approvalId,
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        }
        HumanApproval approval = approvalOpt.get();

        // 4. Approval Hierarchy Validation
        if (!approval.getProject().getId().equals(projectId) ||
            !approval.getRepository().getId().equals(repositoryId) ||
            !approval.getTask().getId().equals(taskId) ||
            !approval.getCodeChange().getId().equals(codeChangeId)) {
            return ApprovedChangeVerificationResult.failure(
                    VerificationFailureCategory.HIERARCHY_MISMATCH,
                    "Approval record hierarchy does not match requested project/repository/task/change IDs",
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        }

        // 5. Approval Status Check
        if (approval.getStatus() == ApprovalStatus.APPROVAL_STALE) {
            return ApprovedChangeVerificationResult.failure(
                    VerificationFailureCategory.APPROVAL_STALE,
                    "Approval record is marked APPROVAL_STALE: " + approval.getStaleReason(),
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        }
        if (approval.getStatus() != ApprovalStatus.APPROVED) {
            return ApprovedChangeVerificationResult.failure(
                    VerificationFailureCategory.INVALID_STATUS,
                    "Approval record status must be APPROVED, found: " + approval.getStatus(),
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        }

        // 6. Cryptographic ApprovalRecordHash Self-Verification
        if (!ApprovalCanonicalEncoder.verifyApprovalRecordHash(approval)) {
            return ApprovedChangeVerificationResult.failure(
                    VerificationFailureCategory.APPROVAL_INTEGRITY_FAILURE,
                    "Approval record hash self-verification failed (tampering or corruption detected)",
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        }

        // 7. CodeChange Existence & Hierarchy Validation
        Optional<CodeChange> codeChangeOpt = codeChangeRepository.findById(codeChangeId);
        if (codeChangeOpt.isEmpty()) {
            return ApprovedChangeVerificationResult.failure(
                    VerificationFailureCategory.CODE_CHANGE_NOT_FOUND,
                    "CodeChange not found: " + codeChangeId,
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        }
        CodeChange codeChange = codeChangeOpt.get();

        if (codeChange.getTask() == null || !codeChange.getTask().getId().equals(taskId) ||
            codeChange.getRepository() == null || !codeChange.getRepository().getId().equals(repositoryId) ||
            codeChange.getTask().getProject() == null || !codeChange.getTask().getProject().getId().equals(projectId)) {
            return ApprovedChangeVerificationResult.failure(
                    VerificationFailureCategory.HIERARCHY_MISMATCH,
                    "CodeChange hierarchy mismatch against task/repository/project",
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        }

        // 8. CodeChange Hash Comparisons (originalHash, proposedHash, diffHash)
        String currentOrigHash = codeChange.getOriginalHash() != null ? codeChange.getOriginalHash().trim().toLowerCase(Locale.ROOT) : "";
        String approvedOrigHash = approval.getCodeChangeOriginalHash() != null ? approval.getCodeChangeOriginalHash().trim().toLowerCase(Locale.ROOT) : "";
        if (!currentOrigHash.equalsIgnoreCase(approvedOrigHash)) {
            return ApprovedChangeVerificationResult.failure(
                    VerificationFailureCategory.CODE_CHANGE_MISMATCH,
                    "CodeChange originalHash mismatch: current=" + currentOrigHash + ", approved=" + approvedOrigHash,
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        }

        String currentPropHash = codeChange.getProposedHash() != null ? codeChange.getProposedHash().trim().toLowerCase(Locale.ROOT) : "";
        String approvedPropHash = approval.getCodeChangeProposedHash() != null ? approval.getCodeChangeProposedHash().trim().toLowerCase(Locale.ROOT) : "";
        if (!currentPropHash.equalsIgnoreCase(approvedPropHash)) {
            return ApprovedChangeVerificationResult.failure(
                    VerificationFailureCategory.CODE_CHANGE_MISMATCH,
                    "CodeChange proposedHash mismatch: current=" + currentPropHash + ", approved=" + approvedPropHash,
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        }

        String recomputedDiffHash = diffGeneratorService.computeSha256Hash(codeChange.getDiff() != null ? codeChange.getDiff() : "");
        String approvedDiffHash = approval.getCodeChangeDiffHash() != null ? approval.getCodeChangeDiffHash().trim().toLowerCase(Locale.ROOT) : "";
        if (recomputedDiffHash == null || !recomputedDiffHash.equalsIgnoreCase(approvedDiffHash)) {
            return ApprovedChangeVerificationResult.failure(
                    VerificationFailureCategory.CODE_CHANGE_MISMATCH,
                    "CodeChange diffHash mismatch: recomputed=" + recomputedDiffHash + ", approved=" + approvedDiffHash,
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        }

        // 9. CodeReview Existence, Hierarchy & Status Validation
        Optional<CodeReview> reviewOpt = codeReviewRepository.findById(approval.getReview().getId());
        if (reviewOpt.isEmpty()) {
            return ApprovedChangeVerificationResult.failure(
                    VerificationFailureCategory.REVIEW_NOT_FOUND,
                    "CodeReview not found: " + approval.getReview().getId(),
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        }
        CodeReview review = reviewOpt.get();

        if (review.getCodeChange() == null || !review.getCodeChange().getId().equals(codeChangeId) ||
            !review.getTask().getId().equals(taskId) || !review.getRepository().getId().equals(repositoryId) ||
            !review.getProject().getId().equals(projectId)) {
            return ApprovedChangeVerificationResult.failure(
                    VerificationFailureCategory.REVIEW_MISMATCH,
                    "CodeReview hierarchy/change binding mismatch",
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        }

        if (review.getStatus() != ReviewStatus.COMPLETED) {
            return ApprovedChangeVerificationResult.failure(
                    VerificationFailureCategory.REVIEW_NOT_COMPLETED,
                    "CodeReview status is not COMPLETED: " + review.getStatus(),
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        }

        // 10. ReviewContext Stability & Context Hash Re-Evaluation
        ReviewContext currentContext = reviewContextService.buildReviewContext(projectId, repositoryId, taskId, codeChangeId, review.getReviewMode(), requester);
        if (currentContext == null || currentContext.isStale()) {
            return ApprovedChangeVerificationResult.failure(
                    VerificationFailureCategory.CONTEXT_MISMATCH,
                    "ReviewContext is stale or invalid",
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        }
        String currentContextHash = currentContext.reviewContextHash() != null ? currentContext.reviewContextHash().trim().toLowerCase(Locale.ROOT) : "";
        String approvedContextHash = approval.getReviewContextHash() != null ? approval.getReviewContextHash().trim().toLowerCase(Locale.ROOT) : "";
        if (!currentContextHash.equals(approvedContextHash) || !review.getReviewContextHash().equalsIgnoreCase(approvedContextHash)) {
            return ApprovedChangeVerificationResult.failure(
                    VerificationFailureCategory.CONTEXT_MISMATCH,
                    "ReviewContextHash drifted: current=" + currentContextHash + ", approved=" + approvedContextHash,
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        }

        // 11. Authoritative Base SHA Verification
        Optional<Repository> repoOpt = repositoryRepository.findById(repositoryId);
        if (repoOpt.isPresent()) {
            Repository repo = repoOpt.get();
            Optional<RepositoryBranch> branchOpt = repositoryBranchRepository.findByRepositoryIdAndName(repositoryId, repo.getDefaultBranch());
            if (branchOpt.isPresent() && branchOpt.get().getCommitSha() != null) {
                String dbBaseSha = branchOpt.get().getCommitSha().trim().toLowerCase(Locale.ROOT);
                String approvedBaseSha = approval.getRepositoryBaseSha() != null ? approval.getRepositoryBaseSha().trim().toLowerCase(Locale.ROOT) : "";
                if (!dbBaseSha.equals(approvedBaseSha)) {
                    return ApprovedChangeVerificationResult.failure(
                            VerificationFailureCategory.BASE_SHA_MISMATCH,
                            "Repository default branch SHA drifted: current=" + dbBaseSha + ", approved=" + approvedBaseSha,
                            approvalId, projectId, repositoryId, taskId, codeChangeId
                    );
                }
            }
        }

        // 12. Approved Files Path Security & Validation
        List<String> approvedFilesList = parseAndValidateApprovedFiles(approval.getApprovedFiles(), codeChange.getFilePath());
        if (approvedFilesList == null) {
            return ApprovedChangeVerificationResult.failure(
                    VerificationFailureCategory.PATH_VALIDATION_FAILED,
                    "Approved files path validation failed (traversal, control chars, or mismatch detected)",
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        }

        // All 12 checks passed!
        return ApprovedChangeVerificationResult.valid(
                approval.getId(),
                projectId,
                repositoryId,
                taskId,
                codeChangeId,
                review.getId(),
                approval.getApprovalVersion(),
                approval.getRepositoryBaseSha(),
                approvedOrigHash,
                approvedPropHash,
                approvedDiffHash,
                approvedContextHash,
                approvedFilesList,
                approval.getApprovalRecordHash(),
                approval.getApprovedByUser().getId()
        );
    }

    private List<String> parseAndValidateApprovedFiles(String jsonFiles, String codeChangeFilePath) {
        if (jsonFiles == null || jsonFiles.isBlank()) {
            return null;
        }

        List<String> rawPaths;
        try {
            rawPaths = objectMapper.readValue(jsonFiles, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            rawPaths = List.of(jsonFiles.trim());
        }

        if (rawPaths.isEmpty()) {
            return null;
        }

        Set<String> uniquePaths = new HashSet<>();
        List<String> validatedPaths = new ArrayList<>();

        String cleanCodeChangePath = sanitizePath(codeChangeFilePath);
        if (cleanCodeChangePath == null) {
            return null;
        }

        for (String raw : rawPaths) {
            String clean = sanitizePath(raw);
            if (clean == null) {
                return null; // Failed security checks
            }

            if (!uniquePaths.add(clean)) {
                return null; // Duplicate path
            }

            if (!clean.equalsIgnoreCase(cleanCodeChangePath)) {
                return null; // Does not match CodeChange path
            }

            validatedPaths.add(clean);
        }

        return validatedPaths;
    }

    private String sanitizePath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }

        // Normalize Unicode
        String normalized = Normalizer.normalize(path.trim(), Normalizer.Form.NFC);

        // Reject control characters or null bytes
        for (char c : normalized.toCharArray()) {
            if (c < 32 || c == 127) {
                return null;
            }
        }

        // Reject absolute paths
        if (normalized.startsWith("/") || normalized.startsWith("\\") || normalized.matches("^[a-zA-Z]:.*")) {
            return null;
        }

        // Replace backslashes
        String clean = normalized.replace("\\", "/").replaceAll("^/+", "");

        // Reject traversal sequences
        if (clean.contains("../") || clean.contains("./") || clean.equals("..") || clean.equals(".")) {
            return null;
        }

        return clean;
    }
}
