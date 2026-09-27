package com.repopilot.approval.dto;

import com.repopilot.approval.entity.AuditEventType;
import com.repopilot.approval.entity.PublicationAuditEvent;

import java.time.Instant;
import java.util.UUID;

public record PublicationAuditEventResponseDto(
        UUID id,
        Long eventSequence,
        Instant eventTimestamp,
        UUID projectId,
        UUID repositoryId,
        UUID taskId,
        UUID codeChangeId,
        UUID approvalId,
        UUID publicationId,
        UUID actorUserId,
        String actorEmail,
        String actorRole,
        AuditEventType eventType,
        String previousState,
        String resultingState,
        String githubRepoId,
        String repoOwner,
        String repoName,
        String baseBranch,
        String branchName,
        String commitSha,
        Long githubPrNumber,
        String prUrl,
        String outcomeCategory,
        String reasonCode,
        String summary
) {
    public static PublicationAuditEventResponseDto fromEntity(PublicationAuditEvent event) {
        return new PublicationAuditEventResponseDto(
                event.getId(),
                event.getEventSequence(),
                event.getEventTimestamp(),
                event.getProject() != null ? event.getProject().getId() : null,
                event.getRepository() != null ? event.getRepository().getId() : null,
                event.getTask() != null ? event.getTask().getId() : null,
                event.getCodeChange() != null ? event.getCodeChange().getId() : null,
                event.getApproval() != null ? event.getApproval().getId() : null,
                event.getPublication() != null ? event.getPublication().getId() : null,
                event.getActorUser() != null ? event.getActorUser().getId() : null,
                event.getActorEmail(),
                event.getActorRole(),
                event.getEventType(),
                event.getPreviousState(),
                event.getResultingState(),
                event.getGithubRepoId(),
                event.getRepoOwner(),
                event.getRepoName(),
                event.getBaseBranch(),
                event.getBranchName(),
                event.getCommitSha(),
                event.getGithubPrNumber(),
                event.getPrUrl(),
                event.getOutcomeCategory(),
                event.getReasonCode(),
                event.getSummary()
        );
    }
}
