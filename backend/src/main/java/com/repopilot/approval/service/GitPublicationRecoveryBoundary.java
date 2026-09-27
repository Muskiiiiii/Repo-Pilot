package com.repopilot.approval.service;

import com.repopilot.approval.dto.GitPullRequestResult;
import com.repopilot.user.entity.User;

import java.util.UUID;

/**
 * Phase 12.2-F-E — Publication Idempotency & Recovery Boundary Interface.
 *
 * Orchestrates and recovers the complete external publication workflow (F-C branch publication and F-D PR creation)
 * in an idempotent, crash-resilient, and failure-aware manner across worker crashes, network timeouts, Redis lease expirations,
 * and stale database states.
 */
public interface GitPublicationRecoveryBoundary {

    /**
     * Idempotently recovers or executes the publication and pull request creation workflow for an approved change.
     *
     * @param projectId    authoritative project ID
     * @param repositoryId authoritative repository ID
     * @param taskId       authoritative task ID
     * @param codeChangeId authoritative code change ID
     * @param approvalId   authoritative human approval ID
     * @param requester    authoritative requesting user
     * @return GitPullRequestResult containing final PR metadata or fail-closed error response
     */
    GitPullRequestResult recoverOrPublish(
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            UUID approvalId,
            User requester
    );
}
