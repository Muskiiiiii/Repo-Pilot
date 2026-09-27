package com.repopilot.github.security;

import com.repopilot.approval.entity.HumanApproval;
import com.repopilot.approval.repository.HumanApprovalRepository;
import com.repopilot.audit.service.AuditLogService;
import com.repopilot.common.exception.ApiException;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.task.entity.Task;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.user.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class GithubCredentialProviderImpl implements GithubCredentialProvider {

    private static final Logger logger = LoggerFactory.getLogger(GithubCredentialProviderImpl.class);

    private final ProjectAuthorizationService projectAuthorizationService;
    private final HumanApprovalRepository humanApprovalRepository;
    private final RepositoryRepository repositoryRepository;
    private final TaskRepository taskRepository;
    private final TokenEncryptionService tokenEncryptionService;
    private final AuditLogService auditLogService;

    public GithubCredentialProviderImpl(
            ProjectAuthorizationService projectAuthorizationService,
            HumanApprovalRepository humanApprovalRepository,
            RepositoryRepository repositoryRepository,
            TaskRepository taskRepository,
            TokenEncryptionService tokenEncryptionService,
            AuditLogService auditLogService
    ) {
        this.projectAuthorizationService = projectAuthorizationService;
        this.humanApprovalRepository = humanApprovalRepository;
        this.repositoryRepository = repositoryRepository;
        this.taskRepository = taskRepository;
        this.tokenEncryptionService = tokenEncryptionService;
        this.auditLogService = auditLogService;
    }

    @Override
    @Transactional(readOnly = true)
    public GithubCredentialContext getGithubCredential(
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID approvalId,
            User requester
    ) {
        if (requester == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authenticated requester required");
        }

        if (projectId == null || repositoryId == null || taskId == null || approvalId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETERS", "All operation entity IDs are required");
        }

        // 1. Enforce Server-Side Project Role Authorization (Requires MEMBER, ADMIN, OWNER)
        Project project = projectAuthorizationService.getAuthorizedProject(projectId, requester);
        projectAuthorizationService.requireMinimumRole(project, requester, ProjectRole.MEMBER);

        // 2. Validate Composite Entity Hierarchy & Binding
        HumanApproval approval = humanApprovalRepository.findById(approvalId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "APPROVAL_NOT_FOUND", "HumanApproval entity not found"));
        Repository repository = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", "Repository entity not found"));
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task entity not found"));

        if (!approval.getStatus().isApproved()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "APPROVAL_STALE", "HumanApproval is not in APPROVED status");
        }

        if (!approval.getProject().getId().equals(projectId) ||
            !approval.getRepository().getId().equals(repositoryId) ||
            !approval.getTask().getId().equals(taskId) ||
            !repository.getProject().getId().equals(projectId) ||
            !task.getProject().getId().equals(projectId)) {
            logger.warn("Hierarchy mismatch during credential access request by user {}: proj={}, repo={}, task={}, approval={}",
                    requester.getId(), projectId, repositoryId, taskId, approvalId);
            throw new ApiException(HttpStatus.FORBIDDEN, "HIERARCHY_MISMATCH", "Entity hierarchy mismatch against approval or project binding");
        }

        // 3. Retrieve Encrypted Token from Requester Entity
        String encryptedToken = requester.getGithubAccessToken();
        if (encryptedToken == null || encryptedToken.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "TOKEN_UNAVAILABLE", "GitHub account is not connected for requester");
        }

        // 4. JIT Token Decryption
        String decryptedToken;
        try {
            decryptedToken = tokenEncryptionService.decrypt(encryptedToken);
        } catch (Exception e) {
            logger.error("Failed to decrypt GitHub access token for user {}: {}", requester.getId(), e.getMessage());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "DECRYPTION_FAILED", "Failed to decrypt GitHub access token");
        }

        if (decryptedToken == null || decryptedToken.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "DECRYPTION_FAILED", "Decrypted token is empty or invalid");
        }

        // 5. Audit Logging (Zero Credentials Logged)
        String username = requester.getGithubUsername() != null && !requester.getGithubUsername().isBlank() ? requester.getGithubUsername() : "x-access-token";
        auditLogService.logAction(
                requester, project, task,
                "CREDENTIAL_ACCESS_REQUESTED", "HUMAN_APPROVAL", approvalId,
                "{\"username\":\"" + username + "\"}"
        );

        logger.info("Successfully acquired JIT GitHub credential context for user {} on approval {}", requester.getId(), approvalId);

        return new GithubCredentialContext(decryptedToken.toCharArray(), username);
    }
}
