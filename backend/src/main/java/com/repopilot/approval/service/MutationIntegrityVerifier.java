package com.repopilot.approval.service;

import com.repopilot.approval.dto.MutationIntegrityResult;
import com.repopilot.user.entity.User;

import java.util.UUID;

public interface MutationIntegrityVerifier {

    MutationIntegrityResult verifyMutationIntegrity(
            UUID approvalId,
            UUID operationId,
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            User requester
    );
}
