package com.repopilot.approval.service;

import com.repopilot.approval.dto.GitPushResult;
import com.repopilot.user.entity.User;

import java.util.UUID;

public interface GitRemotePushBoundary {

    GitPushResult pushApprovedCommitToRemote(
            UUID approvalId,
            UUID operationId,
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            User requester
    );
}
