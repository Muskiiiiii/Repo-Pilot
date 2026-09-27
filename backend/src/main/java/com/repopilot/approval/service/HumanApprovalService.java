package com.repopilot.approval.service;

import com.repopilot.approval.dto.HumanApprovalCreateRequest;
import com.repopilot.approval.dto.HumanApprovalResponseDto;
import com.repopilot.user.entity.User;

import java.util.List;
import java.util.UUID;

public interface HumanApprovalService {

    HumanApprovalResponseDto createApproval(
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            UUID reviewId,
            HumanApprovalCreateRequest request,
            User requester
    );

    HumanApprovalResponseDto getApprovalById(UUID projectId, UUID approvalId, User requester);

    List<HumanApprovalResponseDto> getApprovalsForCodeChange(UUID projectId, UUID codeChangeId, User requester);

    List<HumanApprovalResponseDto> getApprovalsForTask(UUID projectId, UUID taskId, User requester);

    HumanApprovalResponseDto revokeApproval(UUID projectId, UUID approvalId, String reason, User requester);

    boolean verifyApprovalIntegrity(UUID approvalId);
}
