package com.repopilot.approval.dto;

import java.time.Instant;
import java.util.UUID;

public record MutationWorkspaceResult(
        UUID operationId,
        UUID approvalId,
        UUID projectId,
        UUID repositoryId,
        UUID taskId,
        String workspacePath,
        String workspaceRepoPath,
        String baselineTreeHash,
        String workspaceInitialTreeHash,
        long fileCount,
        long totalByteSize,
        Instant createdAt
) {
}
