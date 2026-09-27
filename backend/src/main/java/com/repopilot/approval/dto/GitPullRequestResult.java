package com.repopilot.approval.dto;

import java.time.Instant;
import java.util.UUID;

public record GitPullRequestResult(
        UUID operationId,
        UUID approvalId,
        UUID projectId,
        UUID repositoryId,
        UUID taskId,
        UUID codeChangeId,
        Long githubPrNumber,
        String prUrl,
        String branchName,
        String baseBranch,
        String commitSha,
        String title,
        PullRequestPublicationStatus status,
        String failureCategory,
        String failureReason,
        Instant createdAt
) {
    public static GitPullRequestResult success(
            UUID operationId,
            UUID approvalId,
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            Long githubPrNumber,
            String prUrl,
            String branchName,
            String baseBranch,
            String commitSha,
            String title,
            PullRequestPublicationStatus status
    ) {
        return new GitPullRequestResult(
                operationId,
                approvalId,
                projectId,
                repositoryId,
                taskId,
                codeChangeId,
                githubPrNumber,
                prUrl,
                branchName,
                baseBranch,
                commitSha,
                title,
                status,
                null,
                null,
                Instant.now()
        );
    }

    public static GitPullRequestResult failure(
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
        return new GitPullRequestResult(
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
                status,
                failureCategory,
                failureReason,
                Instant.now()
        );
    }

    public boolean isCreated() {
        return status != null && status.isPrCreated();
    }
}
