package com.repopilot.github.security;

import com.repopilot.user.entity.User;

import java.util.UUID;

public interface GithubCredentialProvider {

    GithubCredentialContext getGithubCredential(
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID approvalId,
            User requester
    );
}
