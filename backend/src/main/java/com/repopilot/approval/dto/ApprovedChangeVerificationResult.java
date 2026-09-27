package com.repopilot.approval.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ApprovedChangeVerificationResult(
        boolean isValid,
        VerificationFailureCategory failureCategory,
        String failureReason,
        UUID approvalId,
        UUID projectId,
        UUID repositoryId,
        UUID taskId,
        UUID codeChangeId,
        UUID reviewId,
        Integer approvalVersion,
        String repositoryBaseSha,
        String codeChangeOriginalHash,
        String codeChangeProposedHash,
        String codeChangeDiffHash,
        String reviewContextHash,
        List<String> approvedFiles,
        String approvalRecordHash,
        UUID approvedByUserId,
        Instant verifiedAt
) {
    public static ApprovedChangeVerificationResult valid(
            UUID approvalId,
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            UUID reviewId,
            Integer approvalVersion,
            String repositoryBaseSha,
            String codeChangeOriginalHash,
            String codeChangeProposedHash,
            String codeChangeDiffHash,
            String reviewContextHash,
            List<String> approvedFiles,
            String approvalRecordHash,
            UUID approvedByUserId
    ) {
        return new ApprovedChangeVerificationResult(
                true,
                VerificationFailureCategory.NONE,
                null,
                approvalId,
                projectId,
                repositoryId,
                taskId,
                codeChangeId,
                reviewId,
                approvalVersion,
                repositoryBaseSha,
                codeChangeOriginalHash,
                codeChangeProposedHash,
                codeChangeDiffHash,
                reviewContextHash,
                approvedFiles,
                approvalRecordHash,
                approvedByUserId,
                Instant.now()
        );
    }

    public static ApprovedChangeVerificationResult failure(
            VerificationFailureCategory category,
            String reason,
            UUID approvalId,
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId
    ) {
        return new ApprovedChangeVerificationResult(
                false,
                category,
                reason,
                approvalId,
                projectId,
                repositoryId,
                taskId,
                codeChangeId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                Instant.now()
        );
    }
}
