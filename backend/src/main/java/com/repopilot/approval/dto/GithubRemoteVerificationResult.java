package com.repopilot.approval.dto;

import java.util.UUID;

public record GithubRemoteVerificationResult(
        boolean verified,
        GithubRemoteVerificationStatus status,
        String failureReason,
        UUID approvalId,
        UUID projectId,
        UUID repositoryId,
        UUID taskId,
        UUID codeChangeId,
        String repoPilotRepoId,
        String githubRepoId,
        String ownerName,
        String repoName,
        String defaultBranch,
        String defaultBranchSha,
        String expectedBaseSha
) {
    public boolean isVerified() {
        return verified;
    }

    public static GithubRemoteVerificationResult verified(
            UUID approvalId, UUID projectId, UUID repositoryId, UUID taskId, UUID codeChangeId,
            String repoPilotRepoId, String githubRepoId, String ownerName, String repoName,
            String defaultBranch, String defaultBranchSha, String expectedBaseSha
    ) {
        return new GithubRemoteVerificationResult(
                true, GithubRemoteVerificationStatus.VERIFIED, null,
                approvalId, projectId, repositoryId, taskId, codeChangeId,
                repoPilotRepoId, githubRepoId, ownerName, repoName,
                defaultBranch, defaultBranchSha, expectedBaseSha
        );
    }

    public static GithubRemoteVerificationResult failure(
            GithubRemoteVerificationStatus status, String failureReason,
            UUID approvalId, UUID projectId, UUID repositoryId, UUID taskId, UUID codeChangeId
    ) {
        return new GithubRemoteVerificationResult(
                false, status, failureReason,
                approvalId, projectId, repositoryId, taskId, codeChangeId,
                null, null, null, null, null, null, null
        );
    }
}
