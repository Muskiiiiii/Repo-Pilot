package com.repopilot.approval.service;

import com.repopilot.approval.dto.PatchMaterializationResult;
import com.repopilot.user.entity.User;

import java.util.UUID;

public interface ApprovedPatchMaterializer {

    PatchMaterializationResult materializeApprovedPatch(
            UUID approvalId,
            UUID operationId,
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            User requester
    );
}
