package com.repopilot.approval.dto;

import com.repopilot.approval.entity.ApprovalStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record HumanApprovalResponseDto(
        UUID approvalId,
        UUID projectId,
        UUID repositoryId,
        UUID taskId,
        UUID codeChangeId,
        UUID reviewId,
        Integer approvalVersion,
        ApprovalStatus status,
        String repositoryBaseSha,
        String codeChangeOriginalHash,
        String codeChangeProposedHash,
        String codeChangeDiffHash,
        String reviewContextHash,
        List<String> approvedFiles,
        Integer approvedChangeCount,
        String approvalRecordHash,
        UUID approvedByUserId,
        String approvedByUserEmail,
        String approvedByUserRole,
        Instant approvedAt,
        String staleReason,
        String idempotencyKey,
        Instant createdAt
) {
}
