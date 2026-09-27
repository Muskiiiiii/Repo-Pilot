package com.repopilot.approval.service;

import com.repopilot.approval.dto.ApprovedChangeVerificationResult;
import com.repopilot.user.entity.User;

import java.util.UUID;

public interface ApprovedChangeVerifier {

    ApprovedChangeVerificationResult verifyApprovedChange(
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            UUID approvalId,
            User requester
    );
}
