package com.repopilot.approval.service;

import com.repopilot.approval.dto.GithubRemoteVerificationResult;
import com.repopilot.user.entity.User;

import java.util.UUID;

public interface GithubRemoteIdentityVerifier {

    GithubRemoteVerificationResult verifyGithubRemoteIdentity(
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            UUID approvalId,
            User requester,
            String githubAccessToken
    );
}
