package com.repopilot.approval.dto;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public record GitMutationResult(
        UUID operationId,
        UUID approvalId,
        UUID projectId,
        UUID repositoryId,
        UUID taskId,
        UUID codeChangeId,
        String branchName,
        String commitSha,
        String parentCommitSha,
        String treeSha,
        List<String> committedPaths,
        GitMutationStatus status,
        String failureReason,
        Instant committedAt
) {
    public static GitMutationResult verified(
            UUID operationId,
            UUID approvalId,
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            String branchName,
            String commitSha,
            String parentCommitSha,
            String treeSha,
            List<String> committedPaths
    ) {
        return new GitMutationResult(
                operationId,
                approvalId,
                projectId,
                repositoryId,
                taskId,
                codeChangeId,
                branchName,
                commitSha,
                parentCommitSha,
                treeSha,
                committedPaths != null ? List.copyOf(committedPaths) : Collections.emptyList(),
                GitMutationStatus.COMMITTED,
                null,
                Instant.now()
        );
    }

    public static GitMutationResult failure(
            UUID operationId,
            UUID approvalId,
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            GitMutationStatus status,
            String failureReason
    ) {
        return new GitMutationResult(
                operationId,
                approvalId,
                projectId,
                repositoryId,
                taskId,
                codeChangeId,
                null,
                null,
                null,
                null,
                Collections.emptyList(),
                status,
                failureReason,
                Instant.now()
        );
    }

    public boolean isCommitted() {
        return status != null && status.isCommitted();
    }
}
