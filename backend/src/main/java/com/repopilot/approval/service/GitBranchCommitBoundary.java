package com.repopilot.approval.service;

import com.repopilot.approval.dto.GitMutationResult;
import com.repopilot.user.entity.User;

import java.util.UUID;

public interface GitBranchCommitBoundary {
    GitMutationResult createBranchAndCommit(
            UUID approvalId,
            UUID operationId,
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            User requester
    );
}
