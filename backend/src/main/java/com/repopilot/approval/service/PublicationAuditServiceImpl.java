package com.repopilot.approval.service;

import com.repopilot.approval.dto.PublicationAuditEventResponseDto;
import com.repopilot.approval.entity.AuditEventType;
import com.repopilot.approval.entity.HumanApproval;
import com.repopilot.approval.entity.PublicationAuditEvent;
import com.repopilot.approval.entity.PullRequestPublication;
import com.repopilot.approval.repository.PublicationAuditEventRepository;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.common.exception.ApiException;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.entity.Repository;
import com.repopilot.task.entity.Task;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.user.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class PublicationAuditServiceImpl implements PublicationAuditService {

    private static final Logger logger = LoggerFactory.getLogger(PublicationAuditServiceImpl.class);

    private static final Pattern PAT_PATTERN = Pattern.compile("ghp_[A-Za-z0-9_]{36,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern BEARER_PATTERN = Pattern.compile("(bearer|oauth|token)\\s+[A-Za-z0-9._~+/-]+=*", Pattern.CASE_INSENSITIVE);
    private static final Pattern FENCE_PATTERN = Pattern.compile("(fence|fencing|redis)[_-]?(token|key)?[:=]\\s*[A-Za-z0-9._-]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("(password|secret|key)[:=]\\s*\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATH_PATTERN = Pattern.compile("/(var|etc|home|usr|tmp|Users)/\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern STACK_TRACE_PATTERN = Pattern.compile("\\tat\\s+[a-zA-Z0-9_.]+\\([^)]+\\)", Pattern.CASE_INSENSITIVE);
    private static final int MAX_METADATA_LENGTH = 8192;

    private final PublicationAuditEventRepository publicationAuditEventRepository;
    private final ProjectAuthorizationService projectAuthorizationService;
    private final TaskRepository taskRepository;

    public PublicationAuditServiceImpl(
            PublicationAuditEventRepository publicationAuditEventRepository,
            ProjectAuthorizationService projectAuthorizationService,
            TaskRepository taskRepository
    ) {
        this.publicationAuditEventRepository = publicationAuditEventRepository;
        this.projectAuthorizationService = projectAuthorizationService;
        this.taskRepository = taskRepository;
    }

    @Override
    @Transactional
    public PublicationAuditEvent recordEvent(
            Project project,
            Repository repository,
            Task task,
            CodeChange codeChange,
            HumanApproval approval,
            PullRequestPublication publication,
            User actor,
            AuditEventType eventType,
            String previousState,
            String resultingState,
            String outcomeCategory,
            String reasonCode,
            String summary,
            String metadataJson
    ) {
        if (project == null || repository == null || task == null || codeChange == null || eventType == null) {
            logger.warn("Incomplete audit event parameters passed to recordEvent");
            return null;
        }

        PublicationAuditEvent event = new PublicationAuditEvent();
        event.setEventTimestamp(Instant.now());
        event.setProject(project);
        event.setRepository(repository);
        event.setTask(task);
        event.setCodeChange(codeChange);
        event.setApproval(approval);
        event.setPublication(publication);

        if (actor != null) {
            event.setActorUser(actor);
            event.setActorEmail(sanitizeText(actor.getEmail()));
            event.setActorRole(actor.getRole() != null ? actor.getRole().name() : "USER");
        } else {
            event.setActorEmail("SYSTEM_WORKER");
            event.setActorRole("SYSTEM");
        }

        event.setEventType(eventType);
        event.setPreviousState(sanitizeText(previousState));
        event.setResultingState(sanitizeText(resultingState));

        event.setGithubRepoId(repository.getGithubRepoId());
        event.setRepoOwner(repository.getOwnerName());
        event.setRepoName(repository.getRepoName());
        event.setBaseBranch(repository.getDefaultBranch());

        if (approval != null) {
            event.setBaseSha(approval.getRepositoryBaseSha());
            event.setDiffHash(approval.getCodeChangeDiffHash());
        }

        if (publication != null) {
            event.setBranchName(publication.getBranchName());
            event.setCommitSha(publication.getCommitSha());
            event.setParentCommitSha(publication.getParentCommitSha());
            event.setTreeSha(publication.getTreeSha());
            event.setGithubPrNumber(publication.getGithubPrNumber());
            event.setPrUrl(sanitizeText(publication.getPrUrl()));
        }

        event.setOutcomeCategory(sanitizeText(outcomeCategory));
        event.setReasonCode(sanitizeText(reasonCode));
        event.setSummary(sanitizeText(summary));

        if (metadataJson != null) {
            String sanitizedMeta = sanitizeText(metadataJson);
            if (sanitizedMeta.length() > MAX_METADATA_LENGTH) {
                sanitizedMeta = sanitizedMeta.substring(0, MAX_METADATA_LENGTH - 36) + "...[TRUNCATED_OVERSIZED_METADATA]";
            }
            event.setMetadataJson(sanitizedMeta);
        }

        return publicationAuditEventRepository.save(event);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PublicationAuditEventResponseDto> getTaskAuditEvents(
            UUID projectId, UUID repositoryId, UUID taskId, User requester
    ) {
        if (requester == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authenticated user required to view audit trail");
        }

        Project project = projectAuthorizationService.getAuthorizedProject(projectId, requester);
        projectAuthorizationService.requireMinimumRole(project, requester, ProjectRole.MEMBER);

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task not found"));

        if (!task.getProject().getId().equals(projectId) || !task.getRepository().getId().equals(repositoryId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "RELATIONSHIP_MISMATCH", "Task resource binding mismatch");
        }

        List<PublicationAuditEvent> events = publicationAuditEventRepository
                .findByProjectIdAndRepositoryIdAndTaskIdOrderByEventSequenceAsc(projectId, repositoryId, taskId);

        return events.stream()
                .map(PublicationAuditEventResponseDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<PublicationAuditEventResponseDto> getCodeChangeAuditEvents(
            UUID projectId, UUID codeChangeId, User requester
    ) {
        if (requester == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authenticated user required to view audit trail");
        }

        Project project = projectAuthorizationService.getAuthorizedProject(projectId, requester);
        projectAuthorizationService.requireMinimumRole(project, requester, ProjectRole.MEMBER);

        List<PublicationAuditEvent> events = publicationAuditEventRepository
                .findByProjectIdAndCodeChangeIdOrderByEventSequenceAsc(projectId, codeChangeId);

        return events.stream()
                .map(PublicationAuditEventResponseDto::fromEntity)
                .collect(Collectors.toList());
    }

    public static String sanitizeText(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        String sanitized = text;
        sanitized = PAT_PATTERN.matcher(sanitized).replaceAll("[REDACTED_PAT_TOKEN]");
        sanitized = BEARER_PATTERN.matcher(sanitized).replaceAll("$1 [REDACTED_TOKEN]");
        sanitized = FENCE_PATTERN.matcher(sanitized).replaceAll("$1 [REDACTED_FENCING_TOKEN]");
        sanitized = PASSWORD_PATTERN.matcher(sanitized).replaceAll("$1=[REDACTED]");
        sanitized = PATH_PATTERN.matcher(sanitized).replaceAll("[REDACTED_PATH]");
        sanitized = STACK_TRACE_PATTERN.matcher(sanitized).replaceAll("[REDACTED_STACK_TRACE]");

        return sanitized;
    }
}
