package com.repopilot.approval.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PatchMaterializationResult(
        UUID operationId,
        UUID approvalId,
        UUID projectId,
        UUID repositoryId,
        UUID taskId,
        UUID codeChangeId,
        String workspaceRepoPath,
        String originalTreeHash,
        String resultingTreeHash,
        int appliedFileCount,
        List<String> approvedChangedPaths,
        Instant materializedAt
) {}
