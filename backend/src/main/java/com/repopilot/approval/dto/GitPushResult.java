package com.repopilot.approval.dto;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public record GitPushResult(
        UUID operationId,
        UUID approvalId,
        UUID projectId,
        UUID repositoryId,
        UUID taskId,
        UUID codeChangeId,
        String branchName,
        String baseBranch,
        String commitSha,
        String parentCommitSha,
        String treeSha,
        String githubRepoId,
        List<String> committedPaths,
        PullRequestPublicationStatus status,
        String failureCategory,
        String failureReason,
        Instant pushedAt
) {
    public static GitPushResult success(
            UUID operationId,
            UUID approvalId,
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            String branchName,
            String baseBranch,
            String commitSha,
            String parentCommitSha,
            String treeSha,
            String githubRepoId,
            List<String> committedPaths,
            PullRequestPublicationStatus status
    ) {
        return new GitPushResult(
                operationId,
                approvalId,
                projectId,
                repositoryId,
                taskId,
                codeChangeId,
                branchName,
                baseBranch,
                commitSha,
                parentCommitSha,
                treeSha,
                githubRepoId,
                committedPaths != null ? List.copyOf(committedPaths) : Collections.emptyList(),
                status,
                null,
                null,
                Instant.now()
        );
    }

    public static GitPushResult failure(
            UUID operationId,
            UUID approvalId,
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            PullRequestPublicationStatus status,
            String failureCategory,
            String failureReason
    ) {
        return new GitPushResult(
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
                null,
                null,
                Collections.emptyList(),
                status,
                failureCategory,
                failureReason,
                Instant.now()
        );
    }

    public boolean isPushed() {
        return status != null && status.isPushed();
    }
}
