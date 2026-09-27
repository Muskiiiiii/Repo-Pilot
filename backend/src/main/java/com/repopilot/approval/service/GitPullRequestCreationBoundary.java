package com.repopilot.approval.service;

import com.repopilot.approval.dto.GitPullRequestResult;
import com.repopilot.user.entity.User;

import java.util.UUID;

public interface GitPullRequestCreationBoundary {

    GitPullRequestResult createPullRequestFromPublication(
            UUID approvalId,
            UUID operationId,
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            User requester
    );
}
